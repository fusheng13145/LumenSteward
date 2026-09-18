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
import com.lumensteward.clawbot.common.util.JsonUtils;
import com.lumensteward.clawbot.domain.context.ContextStore;
import com.lumensteward.clawbot.domain.tool.JsonSchema;
import com.lumensteward.clawbot.domain.tool.Tool;
import com.lumensteward.clawbot.domain.tool.ToolContext;
import com.lumensteward.clawbot.domain.tool.ToolRegistry;
import com.lumensteward.clawbot.domain.tool.ToolResult;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Agent Loop 约束测试（AC-B6~B12 / SC-01~SC-05）。
 *
 * <p>覆盖：轮次上限强制收敛（SC-01/SC-04）、单工具超时降级（SC-03 + SC-05 关键工具中断）。
 */
class AgentLoopConstraintTest {

    private static final int ROUND_CAP = 2;
    private static final String TOOL_NAME = "test_tool";

    @Test
    @DisplayName("③ 达最大轮次强制收敛：轮次=上限，且额外一次收敛调用（SC-01/SC-04）")
    void shouldForceConvergenceAtRoundCap() {
        TestTool tool = new TestTool(TOOL_NAME, false, 0L);
        StubLlmClient llm = new StubLlmClient(List.of(
                toolCallResult(), toolCallResult(), ChatResult.text("好的，信息可能不完整：这是收敛回复")));

        AgentOrchestratorImpl orchestrator = buildOrchestrator(llm, tool, ROUND_CAP, 8000);
        OrchestrationResult result = orchestrator.run(request());

        assertThat(result.rounds()).isEqualTo(ROUND_CAP);
        assertThat(result.llmCalls()).isEqualTo(ROUND_CAP + 1);
        assertThat(result.replyText()).contains("收敛回复");
        assertThat(result.degraded()).isFalse();
    }

    @Test
    @DisplayName("③ 关键工具超时：降级为 TOOL_TIMEOUT 且记录 TIMEOUT（SC-03/SC-05）")
    void shouldDegradeOnCriticalToolTimeout() {
        TestTool tool = new TestTool(TOOL_NAME, true, 500L);
        StubLlmClient llm = new StubLlmClient(List.of(toolCallResult()));

        AgentOrchestratorImpl orchestrator = buildOrchestrator(llm, tool, 1, 50);
        OrchestrationResult result = orchestrator.run(request());

        assertThat(result.fallbackReason()).isEqualTo("TOOL_TIMEOUT");
        assertThat(result.executedTools()).hasSize(1);
        assertThat(result.executedTools().get(0).status()).isEqualTo(ToolStatus.TIMEOUT);
    }

    @Test
    @DisplayName("③ 工具未注册：回注 TOOL_NOT_FOUND，不中断（BR-10）")
    void shouldReinjectWhenToolNotRegistered() {
        TestTool tool = new TestTool(TOOL_NAME, false, 0L);
        // 模型编造了不存在的工具名
        StubLlmClient llm = new StubLlmClient(List.of(
                new ChatResult(null, List.of(ToolCall.function("x1", "ghost_tool", "{}")),
                        null, "tool_calls", null),
                ChatResult.text("抱歉，我没有这个能力。")));

        AgentOrchestratorImpl orchestrator = buildOrchestrator(llm, tool, ROUND_CAP, 8000);
        OrchestrationResult result = orchestrator.run(request());

        assertThat(result.executedTools()).hasSize(1);
        assertThat(result.executedTools().get(0).status()).isEqualTo(ToolStatus.NOT_EXECUTED);
        assertThat(result.executedTools().get(0).toolName()).isEqualTo("ghost_tool");
    }

    private OrchestrationRequest request() {
        return new OrchestrationRequest("trace-1", "openid-test", 1L, "帮我查一下", List.of());
    }

    private AgentOrchestratorImpl buildOrchestrator(LlmClient llm, Tool tool, int maxRounds, int toolTimeoutMs) {
        OrchestrationProperties orchestration = new OrchestrationProperties(
                maxRounds, 3, 25000, toolTimeoutMs, 1, Set.of());
        LlmProperties llmProperties = new LlmProperties("mock", "", "", "mock-model", "",
                15, 20, 8000, 1000);
        return new AgentOrchestratorImpl(llm, new ToolRegistry(List.of(tool)),
                new InMemoryContextStore(), new ContextTrimmer(new HeuristicTokenEstimator()),
                PASS_CHECKER, PASS_SAFETY, new DefaultFallbackService(),
                new NoOpToolCallLogService(), orchestration, llmProperties);
    }

    private static ChatResult toolCallResult() {
        return new ChatResult(null, List.of(ToolCall.function("c1", TOOL_NAME, "{}")),
                null, "tool_calls", null);
    }

    /** 可编程测试工具。 */
    static class TestTool implements Tool {
        private final String name;
        private final boolean critical;
        private final long delayMs;

        TestTool(String name, boolean critical, long delayMs) {
            this.name = name;
            this.critical = critical;
            this.delayMs = delayMs;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "test";
        }

        @Override
        public JsonSchema parametersSchema() {
            return JsonSchema.of("{\"type\":\"object\",\"properties\":{}}");
        }

        @Override
        public ToolResult execute(ToolContext context, JsonNode args) {
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return ToolResult.success(JsonUtils.mapper().createObjectNode(), 1L);
        }

        @Override
        public boolean idempotent() {
            return true;
        }

        @Override
        public boolean critical() {
            return critical;
        }
    }

    /** 顺序返回预置响应的 LLM 客户端。 */
    static class StubLlmClient implements LlmClient {
        private final List<ChatResult> responses;
        private int index = 0;

        StubLlmClient(List<ChatResult> responses) {
            this.responses = responses;
        }

        @Override
        public ChatResult chat(ChatRequest request) {
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

    /** 空操作的同步日志服务（D3：logStart 追加 callSeq 入参）。 */
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

    private static final ConsistencyChecker PASS_CHECKER = (reply, executed) -> ConsistencyVerdict.pass();

    private static final ContentSafetyService PASS_SAFETY = text -> SafetyVerdict.pass();
}
