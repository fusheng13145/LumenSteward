package com.lumensteward.clawbot.orchestrator;

import com.lumensteward.clawbot.application.context.ContextTrimmer;
import com.lumensteward.clawbot.application.context.HeuristicTokenEstimator;
import com.lumensteward.clawbot.application.fallback.DefaultFallbackService;
import com.lumensteward.clawbot.application.orchestrator.AgentOrchestratorImpl;
import com.lumensteward.clawbot.application.orchestrator.model.OrchestrationRequest;
import com.lumensteward.clawbot.application.orchestrator.model.OrchestrationResult;
import com.lumensteward.clawbot.application.orchestrator.model.ToolCallRecord;
import com.lumensteward.clawbot.application.safety.ConsistencyChecker;
import com.lumensteward.clawbot.application.safety.ConsistencyVerdict;
import com.lumensteward.clawbot.application.safety.ContentSafetyService;
import com.lumensteward.clawbot.application.safety.SafetyVerdict;
import com.lumensteward.clawbot.common.enums.ToolStatus;
import com.lumensteward.clawbot.domain.context.RecentImageStore;
import com.lumensteward.clawbot.domain.game.GameSession;
import com.lumensteward.clawbot.domain.game.GameType;
import com.lumensteward.clawbot.domain.model.RecentImage;
import com.lumensteward.clawbot.domain.tool.ToolRegistry;
import com.lumensteward.clawbot.domain.tool.ToolVisibilityContext;
import com.lumensteward.clawbot.domain.tool.impl.AskImageFollowupTool;
import com.lumensteward.clawbot.domain.tool.impl.CodeBreakTool;
import com.lumensteward.clawbot.domain.tool.impl.GameLobbyTool;
import com.lumensteward.clawbot.domain.tool.impl.LifeSimTool;
import com.lumensteward.clawbot.domain.tool.impl.ManagePetProfileTool;
import com.lumensteward.clawbot.domain.tool.impl.PlanRouteTool;
import com.lumensteward.clawbot.domain.tool.impl.QueryExpressTool;
import com.lumensteward.clawbot.domain.tool.impl.QueryWeatherTool;
import com.lumensteward.clawbot.domain.tool.impl.RecognizeImageTool;
import com.lumensteward.clawbot.domain.tool.impl.SynthesizeVoiceTool;
import com.lumensteward.clawbot.domain.tool.impl.TurtleSoupTool;
import com.lumensteward.clawbot.infrastructure.client.llm.LlmClient;
import com.lumensteward.clawbot.infrastructure.client.llm.dto.ChatResult;
import com.lumensteward.clawbot.infrastructure.client.llm.dto.ToolCall;
import com.lumensteward.clawbot.infrastructure.config.properties.LlmProperties;
import com.lumensteward.clawbot.infrastructure.config.properties.OrchestrationProperties;
import com.lumensteward.clawbot.support.InMemoryGameStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 桌游会话与编排链路的接线测试（迭代 4 W19：W10 可见性机制的第二个真实消费方）。
 *
 * <p>验证「两头接上」这一 W19 最容易漏的部分：
 * <ul>
 *   <li><b>会话门控：</b>无局 ⇒ 三个桌游工具不进 Schema、大厅恒在；有局 ⇒ 仅当局游戏具出现
 *       （P2「每轮下发 Schema ≤8」不被广度扩张突破的机制证据）；</li>
 *   <li><b>对局闭环：</b>大厅开局真实执行且会话落库，汤面经工具结果进入模型入参；
 *       进行中工具（密码破译）真实结算并关局；</li>
 *   <li><b>Schema 预算对账：</b>默认配置（示例天气暗启动）下空闲 6 / 游戏中+识图缓存 8，恰在 P2 上限内。</li>
 * </ul>
 */
class OrchestratorGameWiringTest {

    private static final String OPENID = "openid-game-wiring";
    private static final Set<String> DEFAULT_DISABLED = Set.of("query_weather");

    @Test
    @DisplayName("W19 ①：无局 ⇒ 只见大厅不见局内工具；海龟汤进行中 ⇒ 仅 turtle_soup 出现")
    void gameToolsVisibleOnlyWhenOwnSessionActive() {
        InMemoryGameStore store = new InMemoryGameStore();
        CapturingLlmClient llm = new CapturingLlmClient(List.of(ChatResult.text("好的")));

        build(llm, store).run(request("有什么好玩的"));
        assertThat(llm.lastToolNames()).contains("game_lobby")
                .doesNotContain("turtle_soup", "code_break", "life_sim");

        store.save(OPENID, GameSession.forTurtleSoup("ts001", Instant.now()));
        build(llm, store).run(request("给个提示"));
        assertThat(llm.lastToolNames()).contains("game_lobby", "turtle_soup")
                .doesNotContain("code_break", "life_sim");
    }

    @Test
    @DisplayName("W19 ②：大厅开局真实执行、会话落库，汤面与主持人须知经工具结果进入模型入参")
    void lobbyStartPersistsSessionAndBriefsModel() {
        InMemoryGameStore store = new InMemoryGameStore();
        CapturingLlmClient llm = new CapturingLlmClient(List.of(
                new ChatResult(null, List.of(ToolCall.function("g1", "game_lobby",
                        "{\"action\":\"start\",\"game\":\"turtle_soup\"}")), null, "tool_calls", null),
                ChatResult.text("（Mock）汤面：男人要一杯水，酒保却拿枪指着他……")));

        OrchestrationResult result = build(llm, store).run(request("来玩海龟汤"));

        assertThat(result.degraded()).isFalse();
        assertThat(result.executedTools()).hasSize(1);
        ToolCallRecord record = result.executedTools().get(0);
        assertThat(record.toolName()).isEqualTo("game_lobby");
        assertThat(record.status()).isEqualTo(ToolStatus.SUCCESS);
        assertThat(store.find(OPENID).orElseThrow().game()).isEqualTo(GameType.TURTLE_SOUP);
        assertThat(llm.lastToolResultContents()).anySatisfy(
                content -> assertThat(content).contains("surface").contains("host_note"));
    }

    @Test
    @DisplayName("W19 ③：密码破译在局内真实结算——猜中关局，答案只出现在工具结果里")
    void codeBreakResolvesInSessionAndCloses() {
        InMemoryGameStore store = new InMemoryGameStore();
        store.save(OPENID, GameSession.forCodeBreak("0123", Instant.now()));
        CapturingLlmClient llm = new CapturingLlmClient(List.of(
                new ChatResult(null, List.of(ToolCall.function("g2", "code_break",
                        "{\"action\":\"guess\",\"guess\":\"0123\"}")), null, "tool_calls", null),
                ChatResult.text("（Mock）恭喜，答案就是 0123！")));

        OrchestrationResult result = build(llm, store).run(request("我猜 0123"));

        assertThat(result.degraded()).isFalse();
        ToolCallRecord record = result.executedTools().get(0);
        assertThat(record.toolName()).isEqualTo("code_break");
        assertThat(record.status()).isEqualTo(ToolStatus.SUCCESS);
        assertThat(store.find(OPENID)).as("猜中即关局").isEmpty();
        assertThat(llm.lastToolResultContents()).anySatisfy(
                content -> assertThat(content).contains("\"result\":\"solved\"").contains("0123"));
    }

    @Test
    @DisplayName("W19 ④（P2 对账）：默认禁用天气下，空闲下发 6、游戏中+识图缓存下发 8（≤8 不破防）")
    void schemaBudgetStaysWithinCeiling() {
        InMemoryGameStore store = new InMemoryGameStore();
        InMemoryRecentImageStore images = new InMemoryRecentImageStore();
        ToolRegistry full = fullRegistry(store, images);

        int idle = full.dispatchableSchemas(DEFAULT_DISABLED,
                new ToolVisibilityContext(OPENID, false, null)).size();
        assertThat(idle).as("空闲（无缓存无对局）").isEqualTo(6);

        store.save(OPENID, GameSession.forTurtleSoup("ts001", Instant.now()));
        images.save(OPENID, new RecentImage("一只橘猫", "pet", 0.9, Instant.now()));
        int busiest = full.dispatchableSchemas(DEFAULT_DISABLED,
                new ToolVisibilityContext(OPENID, true, "turtle_soup")).size();
        assertThat(busiest).as("最坏情形（对局中+识图缓存）恰在 P2 上限").isEqualTo(8);
    }

    /** 复用 W11 的捕获桩与内存上下文（同包嵌套类），19 参主构造器注入两个真实内存存储。 */
    private AgentOrchestratorImpl build(LlmClient llm, InMemoryGameStore store) {
        OrchestrationProperties orchestration = new OrchestrationProperties(3, 3, 25000, 8000, 1, Set.of());
        LlmProperties llmProperties = new LlmProperties("mock", "", "", "mock-model", "", 15, 20, 8000, 1000);
        ToolRegistry registry = new ToolRegistry(List.of(
                new GameLobbyTool(store), new TurtleSoupTool(store),
                new CodeBreakTool(store), new LifeSimTool(store)));
        return new AgentOrchestratorImpl(llm, registry, new OrchestratorImageWiringTest.InMemoryContextStore(),
                new ContextTrimmer(new HeuristicTokenEstimator()), PASS_CHECKER, PASS_SAFETY,
                new DefaultFallbackService(), new OrchestratorImageWiringTest.NoOpToolCallLogService(),
                orchestration, llmProperties,
                null, null, null, null, null, null, null, null, store);
    }

    /** 与生产容器同源的 11 工具注册表（游戏/识图存储为内存桩，其余依赖 null 只读元信息）。 */
    private ToolRegistry fullRegistry(InMemoryGameStore store, InMemoryRecentImageStore images) {
        return new ToolRegistry(List.of(
                new ManagePetProfileTool(null),
                new QueryExpressTool(null),
                new PlanRouteTool(null),
                new SynthesizeVoiceTool(null, null, null),
                new RecognizeImageTool(null, null, null),
                new AskImageFollowupTool(images),
                new QueryWeatherTool(),
                new GameLobbyTool(store),
                new TurtleSoupTool(store),
                new CodeBreakTool(store),
                new LifeSimTool(store)));
    }

    private static OrchestrationRequest request(String message) {
        return new OrchestrationRequest("trace-game-1", OPENID, 1L, message, List.of());
    }

    private static final ConsistencyChecker PASS_CHECKER = (reply, executed) -> ConsistencyVerdict.pass();
    private static final ContentSafetyService PASS_SAFETY = text -> SafetyVerdict.pass();

    /** 内存识图缓存桩（W19 测试专用，语义同生产：覆盖写、读取命中）。 */
    static final class InMemoryRecentImageStore implements RecentImageStore {
        private final Map<String, RecentImage> images = new HashMap<>();

        @Override
        public boolean save(String openid, RecentImage image) {
            images.put(openid, image);
            return true;
        }

        @Override
        public Optional<RecentImage> find(String openid) {
            return Optional.ofNullable(images.get(openid));
        }
    }

    /** 顺序返回预置响应并捕获请求的 LLM 客户端（镜像 OrchestratorImageWiringTest 的私有桩，公开复制）。 */
    static final class CapturingLlmClient implements LlmClient {
        private final List<ChatResult> responses;
        private List<com.fasterxml.jackson.databind.JsonNode> lastTools = List.of();
        private List<com.lumensteward.clawbot.infrastructure.client.llm.dto.ChatMessage> lastMessages = List.of();
        private int index = 0;

        CapturingLlmClient(List<ChatResult> responses) {
            this.responses = responses;
        }

        List<String> lastToolNames() {
            return lastTools.stream()
                    .map(node -> node.path("function").path("name").asText())
                    .toList();
        }

        List<String> lastToolResultContents() {
            return lastMessages.stream()
                    .filter(com.lumensteward.clawbot.infrastructure.client.llm.dto.ChatMessage::isTool)
                    .map(com.lumensteward.clawbot.infrastructure.client.llm.dto.ChatMessage::content)
                    .toList();
        }

        @Override
        public ChatResult chat(com.lumensteward.clawbot.infrastructure.client.llm.dto.ChatRequest request) {
            lastTools = request.tools() == null ? List.of() : request.tools();
            lastMessages = request.messages() == null ? List.of() : request.messages();
            ChatResult result = responses.get(Math.min(index, responses.size() - 1));
            index++;
            return result;
        }

        @Override
        public com.lumensteward.clawbot.domain.port.model.VisionResult vision(
                com.lumensteward.clawbot.domain.port.model.VisionRequest request) {
            return new com.lumensteward.clawbot.domain.port.model.VisionResult("stub", 1.0, null);
        }

        @Override
        public String provider() {
            return "stub";
        }
    }
}
