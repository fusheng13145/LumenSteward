package com.lumensteward.clawbot.orchestrator;

import com.lumensteward.clawbot.application.anomaly.AnomalyNotice;
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
import com.lumensteward.clawbot.common.enums.AnomalyLayer;
import com.lumensteward.clawbot.common.enums.ToolStatus;
import com.lumensteward.clawbot.domain.tool.ToolRegistry;
import com.lumensteward.clawbot.domain.tool.ToolResult;
import com.lumensteward.clawbot.infrastructure.client.llm.LlmClient;
import com.lumensteward.clawbot.infrastructure.client.llm.dto.ChatResult;
import com.lumensteward.clawbot.infrastructure.client.llm.dto.ToolCall;
import com.lumensteward.clawbot.infrastructure.client.llm.exception.LlmTimeoutException;
import com.lumensteward.clawbot.infrastructure.client.llm.exception.LlmUnavailableException;
import com.lumensteward.clawbot.infrastructure.config.properties.LlmProperties;
import com.lumensteward.clawbot.infrastructure.config.properties.OrchestrationProperties;
import com.lumensteward.clawbot.infrastructure.persistence.service.ToolCallLogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 认知层（L2）异常埋点事件测试（SRS 2.3.5 L2 / 迭代 4 W1）。
 *
 * <p>此前 L2 异常只有一行 WARN 日志，A-3 四层分布在 L2 恒为 0。本测试以「记录型」事件发布器
 * 执行编排，断言 LLM 失败时<b>既照原样降级</b>（回复仍是兜底文案，行为不变），<b>又发布</b>
 * {@link AnomalyNotice}（层次 L2、错误码取自 {@code LlmException#errorType()}）。
 *
 * <p>复用 {@link OrchestrationTraceEmitTest} 的测试替身，避免同一套编排夹具重复维护。
 */
class OrchestratorAnomalyEmitTest {

    private static final int MAX_ROUNDS = 2;

    private final RecordingPublisher publisher = new RecordingPublisher();
    private final LlmClient llm = mock(LlmClient.class);

    @Test
    @DisplayName("LLM 超时：发布 L2/LLM_TIMEOUT（round=0）且照常降级回复")
    void shouldPublishOnLlmTimeout() {
        when(llm.chat(any())).thenThrow(new LlmTimeoutException("llm timeout"));

        OrchestrationResult result = build().run(
                new OrchestrationRequest("trace-l2-1", "openid-l2-timeout", 7L, "帮我查快递", List.of()));

        assertThat(result.replyText()).isNotBlank();
        List<AnomalyNotice> notices = publisher.anomalies();
        assertThat(notices).hasSize(1);
        AnomalyNotice notice = notices.get(0);
        assertThat(notice.layer()).isEqualTo(AnomalyLayer.L2);
        assertThat(notice.errorCode()).isEqualTo("LLM_TIMEOUT");
        assertThat(notice.source()).isEqualTo("orchestrator");
        assertThat(notice.openid()).isEqualTo("openid-l2-timeout");
        assertThat(notice.detail()).isEqualTo("round=0");
    }

    @Test
    @DisplayName("达轮次上限后强制收敛调用失败：发布 L2/LLM_UNAVAILABLE（forced_convergence）")
    void shouldPublishOnForcedConvergenceFailure() {
        ChatResult keepCalling = new ChatResult(null,
                List.of(ToolCall.function("c1", "query_express", "{}")), null, "tool_calls", null);
        when(llm.chat(any())).thenReturn(keepCalling, keepCalling)
                .thenThrow(new LlmUnavailableException("llm down"));

        OrchestrationResult result = build().run(
                new OrchestrationRequest("trace-l2-2", "openid-l2-unavail", 8L, "帮我查快递", List.of()));

        assertThat(result.replyText()).isNotBlank();
        List<AnomalyNotice> notices = publisher.anomalies();
        assertThat(notices).hasSize(1);
        assertThat(notices.get(0).errorCode()).isEqualTo("LLM_UNAVAILABLE");
        assertThat(notices.get(0).detail()).isEqualTo("forced_convergence");
        assertThat(notices.get(0).layer()).isEqualTo(AnomalyLayer.L2);
    }

    @Test
    @DisplayName("链路正常：不发布任何 L2 异常事件")
    void shouldNotPublishOnSuccess() {
        when(llm.chat(any())).thenReturn(ChatResult.text("已为你查询到结果。"));

        build().run(new OrchestrationRequest("trace-l2-3", "openid-l2-ok", 9L, "你好", List.of()));

        assertThat(publisher.anomalies()).isEmpty();
    }

    private AgentOrchestratorImpl build() {
        OrchestrationProperties orchestration = new OrchestrationProperties(
                MAX_ROUNDS, 3, 25000, 8000, 1, Set.of());
        LlmProperties llmProperties = new LlmProperties("mock", "", "", "mock-model", "", 15, 20, 8000, 1000);
        return new AgentOrchestratorImpl(llm,
                new ToolRegistry(List.of(new OrchestrationTraceEmitTest.TestTool("query_express", false, 0L))),
                new OrchestrationTraceEmitTest.InMemoryContextStore(),
                new ContextTrimmer(new HeuristicTokenEstimator()),
                PASS_CHECKER, PASS_SAFETY, new DefaultFallbackService(),
                new NoOpLog(), orchestration, llmProperties, null, null, null, publisher, null);
    }

    private static final ConsistencyChecker PASS_CHECKER =
            (reply, executed) -> ConsistencyVerdict.pass();

    private static final ContentSafetyService PASS_SAFETY = text -> SafetyVerdict.pass();

    /** 只关心 AnomalyNotice 的记录型发布器。 */
    static class RecordingPublisher implements ApplicationEventPublisher {
        private final List<Object> events = new ArrayList<>();

        @Override
        public void publishEvent(Object event) {
            events.add(event);
        }

        @Override
        public void publishEvent(ApplicationEvent event) {
            events.add(event);
        }

        List<AnomalyNotice> anomalies() {
            return events.stream()
                    .filter(AnomalyNotice.class::isInstance)
                    .map(AnomalyNotice.class::cast)
                    .toList();
        }
    }

    /** 不落库的工具日志替身。 */
    static class NoOpLog implements ToolCallLogService {
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
}
