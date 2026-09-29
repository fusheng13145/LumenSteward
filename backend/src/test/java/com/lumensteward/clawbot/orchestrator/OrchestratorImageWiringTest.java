package com.lumensteward.clawbot.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
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
import com.lumensteward.clawbot.domain.context.ContextStore;
import com.lumensteward.clawbot.domain.context.RecentImageStore;
import com.lumensteward.clawbot.domain.model.RecentImage;
import com.lumensteward.clawbot.domain.tool.JsonSchema;
import com.lumensteward.clawbot.domain.tool.Tool;
import com.lumensteward.clawbot.domain.tool.ToolContext;
import com.lumensteward.clawbot.domain.tool.ToolRegistry;
import com.lumensteward.clawbot.domain.tool.ToolResult;
import com.lumensteward.clawbot.domain.tool.impl.AskImageFollowupTool;
import com.lumensteward.clawbot.infrastructure.client.llm.LlmClient;
import com.lumensteward.clawbot.infrastructure.client.llm.dto.ChatMessage;
import com.lumensteward.clawbot.infrastructure.client.llm.dto.ChatRequest;
import com.lumensteward.clawbot.infrastructure.client.llm.dto.ChatResult;
import com.lumensteward.clawbot.infrastructure.client.llm.dto.ToolCall;
import com.lumensteward.clawbot.domain.port.model.VisionRequest;
import com.lumensteward.clawbot.domain.port.model.VisionResult;
import com.lumensteward.clawbot.infrastructure.config.properties.LlmProperties;
import com.lumensteward.clawbot.infrastructure.config.properties.OrchestrationProperties;
import com.lumensteward.clawbot.infrastructure.persistence.service.ToolCallLogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 识图缓存与编排链路的接线测试（迭代 4 W11：W10 机制的首个真实消费者）。
 *
 * <p>验证「两头接上」这一 W11 最容易漏的部分：
 * <ul>
 *   <li><b>可见性：</b>识图缓存存在 ⇒ ask_image 出现在本轮函数 Schema；无缓存 ⇒ 不出现
 *       （W10 交付判据「无缓存图片时该工具不出现在函数 Schema」的机制级证据）；</li>
 *   <li><b>续接：</b>模型发起 ask_image 后，缓存结论作为工具结果进入模型入参
 *       （D1 场景 S2 的「第二问带第一问结论」），且工具真实执行成功。</li>
 * </ul>
 */
class OrchestratorImageWiringTest {

    private static final String OPENID = "openid-img-wiring";
    private static final String CACHED_DESCRIPTION = "一只橘色的成年短毛猫，毛色干净，体态圆润";

    @Test
    @DisplayName("W11 ①：识图缓存存在 ⇒ ask_image 进函数 Schema；无缓存 ⇒ 不出现")
    void askImageVisibleOnlyWhenCachePresent() {
        CapturingLlmClient llm = new CapturingLlmClient(List.of(ChatResult.text("好的")));
        InMemoryRecentImageStore store = new InMemoryRecentImageStore();
        store.save(OPENID, cachedImage());

        AgentOrchestratorImpl withCache = build(llm, store);
        withCache.run(request("它大概多大了"));
        assertThat(llm.lastToolNames()).contains("ask_image");

        AgentOrchestratorImpl withoutCache = build(llm, new InMemoryRecentImageStore());
        withoutCache.run(request("它大概多大了"));
        assertThat(llm.lastToolNames()).doesNotContain("ask_image");
    }

    @Test
    @DisplayName("W11 ②：ask_image 真实执行且缓存结论进入模型入参（S2 形态，免重发图片）")
    void followupCarriesCachedDescriptionIntoModelInput() {
        InMemoryRecentImageStore store = new InMemoryRecentImageStore();
        store.save(OPENID, cachedImage());
        CapturingLlmClient llm = new CapturingLlmClient(List.of(
                new ChatResult(null, List.of(ToolCall.function("a1", "ask_image",
                        "{\"question\":\"它大概多大了\"}")), null, "tool_calls", null),
                ChatResult.text("（Mock）从图片看，这只橘猫大约 2 岁。")));

        OrchestrationResult result = build(llm, store).run(request("它大概多大了"));

        assertThat(result.degraded()).isFalse();
        assertThat(result.executedTools()).hasSize(1);
        ToolCallRecord record = result.executedTools().get(0);
        assertThat(record.toolName()).isEqualTo("ask_image");
        assertThat(record.status()).isEqualTo(ToolStatus.SUCCESS);
        // 缓存结论已作为 tool 结果消息进入第二轮的模型入参（而非要求用户重发图片）
        assertThat(llm.lastToolResultContents()).anySatisfy(
                content -> assertThat(content).contains(CACHED_DESCRIPTION));
    }

    private AgentOrchestratorImpl build(LlmClient llm, RecentImageStore recentImageStore) {
        OrchestrationProperties orchestration = new OrchestrationProperties(3, 3, 25000, 8000, 1, Set.of());
        LlmProperties llmProperties = new LlmProperties("mock", "", "", "mock-model", "", 15, 20, 8000, 1000);
        ToolRegistry registry = new ToolRegistry(List.of(
                new AskImageFollowupTool(recentImageStore), new MarkerTool("marker_tool")));
        // 18 参主构造器：dynamicConfig / 预算 / 长度守卫 / 事件 / 审计 / 任务 / 记忆召回均为 null，
        // 仅注入识图缓存——验证的正是该参数对可见性与执行路径的接线
        return new AgentOrchestratorImpl(llm, registry, new InMemoryContextStore(),
                new ContextTrimmer(new HeuristicTokenEstimator()), PASS_CHECKER, PASS_SAFETY,
                new DefaultFallbackService(), new NoOpToolCallLogService(), orchestration, llmProperties,
                null, null, null, null, null, null, null, recentImageStore);
    }

    private static RecentImage cachedImage() {
        return new RecentImage(CACHED_DESCRIPTION, "pet", 0.9, Instant.now());
    }

    private static OrchestrationRequest request(String message) {
        return new OrchestrationRequest("trace-img-1", OPENID, 1L, message, List.of());
    }

    private static final ConsistencyChecker PASS_CHECKER = (reply, executed) -> ConsistencyVerdict.pass();
    private static final ContentSafetyService PASS_SAFETY = text -> SafetyVerdict.pass();

    /** 内存识图缓存桩（与生产 Redis 实现同语义：覆盖写、故障返回空）。 */
    private static final class InMemoryRecentImageStore implements RecentImageStore {
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

    /** 无副作用的占位工具（用于确认其余工具不受可见性裁剪影响）。 */
    private record MarkerTool(String name) implements Tool {

        @Override
        public String description() {
            return "marker";
        }

        @Override
        public JsonSchema parametersSchema() {
            return JsonSchema.of("{\"type\":\"object\",\"properties\":{}}");
        }

        @Override
        public ToolResult execute(ToolContext context, JsonNode args) {
            return ToolResult.notExecuted("MARKER", "占位工具不执行");
        }

        @Override
        public boolean idempotent() {
            return true;
        }

        @Override
        public boolean critical() {
            return false;
        }
    }

    /** 内存上下文。 */
    static class InMemoryContextStore implements ContextStore {
        private final List<ChatMessage> messages = new ArrayList<>();

        @Override
        public List<ChatMessage> load(String openid) {
            return new ArrayList<>(messages);
        }

        @Override
        public void append(String openid, ChatMessage message) {
            messages.add(message);
        }

        @Override
        public void appendAll(String openid, List<ChatMessage> list) {
            messages.addAll(list);
        }

        @Override
        public void clear(String openid) {
            messages.clear();
        }

        @Override
        public boolean isAvailable() {
            return true;
        }
    }

    /** 空操作的同步日志服务。 */
    static class NoOpToolCallLogService implements ToolCallLogService {
        @Override
        public ToolCallRecord logStart(String traceId, String openid, Long sessionId, ToolCall call,
                                       int round, int callSeq) {
            return new ToolCallRecord(1L, traceId, openid, sessionId,
                    call == null ? null : call.functionName(), callSeq, round, ToolStatus.NOT_EXECUTED,
                    null, null, null, null, 0L);
        }

        @Override
        public void logEnd(ToolCallRecord record, ToolResult result) {
            // 测试：不落库
        }
    }

    /** 顺序返回预置响应并捕获请求的 LLM 客户端（断言 Schema 下发与模型入参）。 */
    static class CapturingLlmClient implements LlmClient {
        private final List<ChatResult> responses;
        private List<JsonNode> lastTools = List.of();
        private List<ChatMessage> lastMessages = List.of();
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
                    .filter(ChatMessage::isTool)
                    .map(ChatMessage::content)
                    .toList();
        }

        @Override
        public ChatResult chat(ChatRequest request) {
            lastTools = request.tools() == null ? List.of() : request.tools();
            lastMessages = request.messages() == null ? List.of() : request.messages();
            ChatResult result = responses.get(Math.min(index, responses.size() - 1));
            index++;
            return result;
        }

        @Override
        public VisionResult vision(VisionRequest request) {
            return new VisionResult("stub", 1.0, null);
        }

        @Override
        public String provider() {
            return "stub";
        }
    }
}
