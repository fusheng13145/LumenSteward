package com.lumensteward.clawbot.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.lumensteward.clawbot.application.context.ContextTrimmer;
import com.lumensteward.clawbot.application.context.HeuristicTokenEstimator;
import com.lumensteward.clawbot.application.fallback.DefaultFallbackService;
import com.lumensteward.clawbot.application.orchestrator.AgentOrchestratorImpl;
import com.lumensteward.clawbot.application.orchestrator.model.OrchestrationRequest;
import com.lumensteward.clawbot.application.orchestrator.model.OrchestrationResult;
import com.lumensteward.clawbot.application.safety.ConsistencyChecker;
import com.lumensteward.clawbot.application.safety.ConsistencyVerdict;
import com.lumensteward.clawbot.application.safety.ContentSafetyService;
import com.lumensteward.clawbot.application.safety.SafetyVerdict;
import com.lumensteward.clawbot.common.enums.ToolStatus;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 「帮助」指令测试（迭代 4 W12/W20：确定性能力自述，真值 = 本轮实际下发集）。
 */
class HelpCommandTest {

    private final LlmClient llm = mock(LlmClient.class);

    @Test
    @DisplayName("「帮助」→ 确定性能力自述：不调 LLM、零工具调用、上下文照常回写")
    void helpAnswersDeterministicallyWithoutLlm() {
        RecordingStore store = new RecordingStore();
        AgentOrchestratorImpl orchestrator = build(store, Set.of());

        OrchestrationResult result = orchestrator.run(request("帮助"));

        verify(llm, never()).chat(any(ChatRequest.class));
        assertThat(result.rounds()).isZero();
        assertThat(result.llmCalls()).isZero();
        assertThat(result.degraded()).isFalse();
        assertThat(result.executedTools()).isEmpty();
        assertThat(result.replyText())
                .contains("当前实际能帮你做的事")
                .contains("查询快递物流轨迹")
                // 条件不可见工具（ask_image，无识图缓存）不得被承诺
                .doesNotContain("无需重发图片");
        assertThat(store.appended).hasSize(2);
        assertThat(store.appended.get(0).content()).isEqualTo("帮助");
        assertThat(store.appended.get(1).content()).contains("查询快递物流轨迹");
    }

    @Test
    @DisplayName("禁用集合参与真值：被灰度关闭的工具不出现在帮助清单")
    void helpHonorsDisabledTools() {
        AgentOrchestratorImpl orchestrator = build(new RecordingStore(), Set.of("query_express"));

        OrchestrationResult result = orchestrator.run(request("你能做什么"));

        assertThat(result.replyText()).doesNotContain("查询快递物流轨迹");
    }

    @Test
    @DisplayName("非帮助指令照常走编排（LLM 被调用）")
    void normalMessageStillGoesThroughEngine() {
        AgentOrchestratorImpl orchestrator = build(new RecordingStore(), Set.of());

        orchestrator.run(request("你好，帮我看看"));

        verify(llm).chat(any(ChatRequest.class));
    }

    @Test
    @DisplayName("系统提示词能力段真值化：能力段由实际下发集生成（W12）")
    void systemPromptCarriesTruthfulCapabilitySection() {
        AgentOrchestratorImpl orchestrator = build(new RecordingStore(), Set.of("query_express"));

        orchestrator.run(request("你好，帮我看看"));

        org.mockito.ArgumentCaptor<ChatRequest> captor =
                org.mockito.ArgumentCaptor.forClass(ChatRequest.class);
        verify(llm).chat(captor.capture());
        ChatMessage system = captor.getValue().messages().stream()
                .filter(m -> "system".equals(m.role()))
                .findFirst().orElseThrow();
        assertThat(system.content())
                .contains("只可调用以下这些")
                .contains("synthesize_voice")
                .doesNotContain("query_express");
    }

    private AgentOrchestratorImpl build(ContextStore store, Set<String> disabledTools) {
        OrchestrationProperties orchestration = new OrchestrationProperties(3, 3, 25000, 8000, 1, disabledTools);
        LlmProperties llmProperties = new LlmProperties("mock", "", "", "mock-model", "", 15, 20, 8000, 1000);
        ToolRegistry registry = new ToolRegistry(List.of(
                new QueryExpressStub(), new VoiceStub()));
        return new AgentOrchestratorImpl(llm, registry, store,
                new ContextTrimmer(new HeuristicTokenEstimator()), PASS_CHECKER, PASS_SAFETY,
                new DefaultFallbackService(), logService, orchestration, llmProperties,
                null, null, null, null, null, null, null, null);
    }

    private static OrchestrationRequest request(String message) {
        return new OrchestrationRequest("trace-help", "openid-help", 1L, message, List.of());
    }

    private static final ConsistencyChecker PASS_CHECKER = (reply, executed) -> ConsistencyVerdict.pass();
    private static final ContentSafetyService PASS_SAFETY = text -> SafetyVerdict.pass();

    private final ToolCallLogService logService = new NoOpToolCallLogService();

    /** 快递查询桩（描述与生产工具同款语义，便于断言能力清单内容）。 */
    private static class QueryExpressStub implements Tool {
        @Override
        public String name() {
            return "query_express";
        }

        @Override
        public String description() {
            return "查询快递物流轨迹。";
        }

        @Override
        public JsonSchema parametersSchema() {
            return JsonSchema.of("{\"type\":\"object\",\"properties\":{}}");
        }

        @Override
        public ToolResult execute(ToolContext context, JsonNode args) {
            return ToolResult.notExecuted("STUB", "桩工具不执行");
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

    /** 语音合成桩。 */
    private static class VoiceStub implements Tool {
        @Override
        public String name() {
            return "synthesize_voice";
        }

        @Override
        public String description() {
            return "将文本合成为语音并发送。";
        }

        @Override
        public JsonSchema parametersSchema() {
            return JsonSchema.of("{\"type\":\"object\",\"properties\":{}}");
        }

        @Override
        public ToolResult execute(ToolContext context, JsonNode args) {
            return ToolResult.notExecuted("STUB", "桩工具不执行");
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

    /** 空操作的同步日志服务（帮助路径不应走到；正常路径只做空记录）。 */
    private static class NoOpToolCallLogService implements ToolCallLogService {
        @Override
        public com.lumensteward.clawbot.application.orchestrator.model.ToolCallRecord logStart(
                String traceId, String openid, Long sessionId,
                com.lumensteward.clawbot.infrastructure.client.llm.dto.ToolCall call,
                int round, int callSeq) {
            return new com.lumensteward.clawbot.application.orchestrator.model.ToolCallRecord(
                    1L, traceId, openid, sessionId,
                    call == null ? null : call.functionName(), callSeq, round, ToolStatus.NOT_EXECUTED,
                    null, null, null, null, 0L);
        }

        @Override
        public void logEnd(
                com.lumensteward.clawbot.application.orchestrator.model.ToolCallRecord record,
                ToolResult result) {
            // 测试：不落库
        }
    }

    /** 记录回写内容的内存上下文。 */
    private static class RecordingStore implements ContextStore {        final List<ChatMessage> appended = new ArrayList<>();

        @Override
        public List<ChatMessage> load(String openid) {
            return List.of();
        }

        @Override
        public void append(String openid, ChatMessage message) {
            appended.add(message);
        }

        @Override
        public void appendAll(String openid, List<ChatMessage> list) {
            appended.addAll(list);
        }

        @Override
        public void clear(String openid) {
            appended.clear();
        }

        @Override
        public boolean isAvailable() {
            return true;
        }
    }
}
