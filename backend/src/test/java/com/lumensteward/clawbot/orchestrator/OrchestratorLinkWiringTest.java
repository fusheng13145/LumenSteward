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
import com.lumensteward.clawbot.domain.game.GameSession;
import com.lumensteward.clawbot.domain.port.model.LinkContent;
import com.lumensteward.clawbot.domain.tool.ToolRegistry;
import com.lumensteward.clawbot.domain.tool.impl.AskImageFollowupTool;
import com.lumensteward.clawbot.domain.tool.impl.CodeBreakTool;
import com.lumensteward.clawbot.domain.tool.impl.GameLobbyTool;
import com.lumensteward.clawbot.domain.tool.impl.LifeSimTool;
import com.lumensteward.clawbot.domain.tool.impl.ManagePetProfileTool;
import com.lumensteward.clawbot.domain.tool.impl.PlanRouteTool;
import com.lumensteward.clawbot.domain.tool.impl.QueryExpressTool;
import com.lumensteward.clawbot.domain.tool.impl.QueryWeatherTool;
import com.lumensteward.clawbot.domain.tool.impl.ReadLinkTool;
import com.lumensteward.clawbot.domain.tool.impl.RecognizeImageTool;
import com.lumensteward.clawbot.domain.tool.impl.SynthesizeVoiceTool;
import com.lumensteward.clawbot.domain.tool.impl.TurtleSoupTool;
import com.lumensteward.clawbot.infrastructure.client.llm.dto.ChatResult;
import com.lumensteward.clawbot.infrastructure.client.llm.dto.ToolCall;
import com.lumensteward.clawbot.infrastructure.config.properties.LlmProperties;
import com.lumensteward.clawbot.infrastructure.config.properties.OrchestrationProperties;
import com.lumensteward.clawbot.support.InMemoryGameStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 链接读取与编排链路的接线测试（迭代 4 W16）：linkPresent 情境门控 + 与 ask_image 的
 * 同轮互斥（P2 预算收口规则）+ read_link 端到端执行。
 */
class OrchestratorLinkWiringTest {

    private static final String OPENID = "openid-link-wiring";
    private static final Set<String> DEFAULT_DISABLED = Set.of("query_weather");
    private static final String LINK_MESSAGE = "（用户分享了一个链接《示例》，地址：https://example.com/hello）";
    private static final String PLAIN_MESSAGE = "帮我看看今天有什么安排";

    @Test
    @DisplayName("W16 ①：本轮含链接 ⇒ read_link 进 Schema；无链接轮次不出现")
    void readLinkVisibleOnlyWhenMessageContainsLink() {
        OrchestratorGameWiringTest.CapturingLlmClient llm =
                new OrchestratorGameWiringTest.CapturingLlmClient(List.of(ChatResult.text("好的")));
        AgentOrchestratorImpl orchestrator = build(llm, new InMemoryGameStore(),
                new OrchestratorGameWiringTest.InMemoryRecentImageStore(), stubPort());

        orchestrator.run(request(LINK_MESSAGE));
        assertThat(llm.lastToolNames()).contains("read_link");

        orchestrator.run(request(PLAIN_MESSAGE));
        assertThat(llm.lastToolNames()).doesNotContain("read_link");
    }

    @Test
    @DisplayName("W16 ②（同轮互斥）：含链接轮次 ask_image 让位；无链接轮次照常出现")
    void askImageYieldsToReadLinkOnLinkTurns() {
        OrchestratorGameWiringTest.InMemoryRecentImageStore images =
                new OrchestratorGameWiringTest.InMemoryRecentImageStore();
        images.save(OPENID, new com.lumensteward.clawbot.domain.model.RecentImage(
                "一只橘猫", "pet", 0.9, Instant.now()));
        OrchestratorGameWiringTest.CapturingLlmClient llm =
                new OrchestratorGameWiringTest.CapturingLlmClient(List.of(ChatResult.text("好的")));
        AgentOrchestratorImpl orchestrator = build(llm, new InMemoryGameStore(), images, stubPort());

        orchestrator.run(request(LINK_MESSAGE));
        assertThat(llm.lastToolNames()).contains("read_link").doesNotContain("ask_image");

        orchestrator.run(request(PLAIN_MESSAGE));
        assertThat(llm.lastToolNames()).contains("ask_image").doesNotContain("read_link");
    }

    @Test
    @DisplayName("W16 ③（P2 对账）：最坏情形（对局中+识图缓存+含链接）恰 8 个，互斥规则保住上限")
    void worstCaseSchemaStaysWithinCeiling() {
        InMemoryGameStore store = new InMemoryGameStore();
        store.save(OPENID, GameSession.forTurtleSoup("ts001", Instant.now()));
        OrchestratorGameWiringTest.InMemoryRecentImageStore images =
                new OrchestratorGameWiringTest.InMemoryRecentImageStore();
        images.save(OPENID, new com.lumensteward.clawbot.domain.model.RecentImage(
                "一只橘猫", "pet", 0.9, Instant.now()));
        OrchestratorGameWiringTest.CapturingLlmClient llm =
                new OrchestratorGameWiringTest.CapturingLlmClient(List.of(ChatResult.text("好的")));

        build(llm, store, images, stubPort()).run(request(LINK_MESSAGE));

        List<String> names = llm.lastToolNames();
        assertThat(names).contains("game_lobby", "turtle_soup", "read_link")
                .doesNotContain("ask_image");
        assertThat(names.size()).as("最坏情形恰在 P2 上限（天气示例工具按默认暗启动剔除）").isEqualTo(8);
        assertThat(names).doesNotContain("query_weather");
    }

    @Test
    @DisplayName("W16 ④：read_link 端到端执行——正文随工具结果进入模型入参")
    void readLinkExecutesAndFeedsContentToModel() {
        OrchestratorGameWiringTest.InMemoryRecentImageStore images =
                new OrchestratorGameWiringTest.InMemoryRecentImageStore();
        OrchestratorGameWiringTest.CapturingLlmClient llm = new OrchestratorGameWiringTest.CapturingLlmClient(List.of(
                new ChatResult(null, List.of(ToolCall.function("l1", "read_link",
                        "{\"url\":\"https://example.com/hello\"}")), null, "tool_calls", null),
                ChatResult.text("（Mock）这篇链接讲了……")));
        com.lumensteward.clawbot.domain.port.LinkFetchPort fetchPort = url ->
                new LinkContent("示例页", "这里是正文要点。", false);
        AgentOrchestratorImpl orchestrator = build(llm, new InMemoryGameStore(), images, fetchPort);

        OrchestrationResult result = orchestrator.run(request(LINK_MESSAGE));

        assertThat(result.degraded()).isFalse();
        ToolCallRecord record = result.executedTools().get(0);
        assertThat(record.toolName()).isEqualTo("read_link");
        assertThat(record.status()).isEqualTo(ToolStatus.SUCCESS);
        assertThat(llm.lastToolResultContents()).anySatisfy(
                content -> assertThat(content).contains("示例页").contains("这里是正文要点。"));
    }

    /** 12 工具注册表（生产全集 + read_link），19 参主构造器注入游戏/识图/抓取三个依赖。 */
    private AgentOrchestratorImpl build(OrchestratorGameWiringTest.CapturingLlmClient llm, InMemoryGameStore store,
                                        OrchestratorGameWiringTest.InMemoryRecentImageStore images,
                                        com.lumensteward.clawbot.domain.port.LinkFetchPort fetchPort) {
        OrchestrationProperties orchestration = new OrchestrationProperties(3, 3, 25000, 8000, 1, DEFAULT_DISABLED);
        LlmProperties llmProperties = new LlmProperties("mock", "", "", "mock-model", "", 15, 20, 8000, 1000);
        ToolRegistry registry = new ToolRegistry(List.of(
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
                new LifeSimTool(store),
                new ReadLinkTool(fetchPort)));
        return new AgentOrchestratorImpl(llm, registry,
                new OrchestratorImageWiringTest.InMemoryContextStore(),
                new ContextTrimmer(new HeuristicTokenEstimator()), PASS_CHECKER, PASS_SAFETY,
                new DefaultFallbackService(), new OrchestratorImageWiringTest.NoOpToolCallLogService(),
                orchestration, llmProperties,
                null, null, null, null, null, null, null, images, store);
    }

    /** 抓取成功桩（可见性与计数用例不消费内容，④ 用例自带内容桩）。 */
    private static com.lumensteward.clawbot.domain.port.LinkFetchPort stubPort() {
        return url -> new LinkContent("示例页", "正文。", false);
    }

    private static OrchestrationRequest request(String message) {
        return new OrchestrationRequest("trace-link-1", OPENID, 1L, message, List.of());
    }

    private static final ConsistencyChecker PASS_CHECKER = (reply, executed) -> ConsistencyVerdict.pass();
    private static final ContentSafetyService PASS_SAFETY = text -> SafetyVerdict.pass();
}
