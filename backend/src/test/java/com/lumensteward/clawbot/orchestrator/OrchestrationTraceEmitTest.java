package com.lumensteward.clawbot.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.lumensteward.clawbot.application.context.ContextTrimmer;
import com.lumensteward.clawbot.application.context.HeuristicTokenEstimator;
import com.lumensteward.clawbot.application.fallback.DefaultFallbackService;
import com.lumensteward.clawbot.application.orchestrator.AgentOrchestratorImpl;
import com.lumensteward.clawbot.application.orchestrator.OrchestrationTracedEvent;
import com.lumensteward.clawbot.application.orchestrator.model.OrchestrationRequest;
import com.lumensteward.clawbot.application.orchestrator.model.OrchestrationSpan;
import com.lumensteward.clawbot.application.orchestrator.model.OrchestrationResult;
import com.lumensteward.clawbot.application.orchestrator.model.ToolCallRecord;
import com.lumensteward.clawbot.application.safety.ConsistencyChecker;
import com.lumensteward.clawbot.application.safety.ConsistencyVerdict;
import com.lumensteward.clawbot.application.safety.ContentSafetyService;
import com.lumensteward.clawbot.application.safety.SafetyVerdict;
import com.lumensteward.clawbot.common.enums.ToolStatus;
import com.lumensteward.clawbot.common.util.JsonUtils;
import com.lumensteward.clawbot.common.util.MaskUtils;
import com.lumensteward.clawbot.domain.context.ContextStore;
import com.lumensteward.clawbot.domain.port.model.VisionRequest;
import com.lumensteward.clawbot.domain.port.model.VisionResult;
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

/**
 * 链路时序 span 组装与超预算判定（A-5 / T6）。
 *
 * <p>经完整构造器注入一个「记录型」事件发布器，执行含工具调用的编排，断言：
 * <ul>
 *   <li>span 组装正确：类型/顺序/命名/轮次，起止自洽（偏移单调、耗时非负、相对偏移 + 耗时 &le; 总耗时）；</li>
 *   <li>超预算判定：{@code totalMs > totalBudgetMs} 时 {@code exceededBudget=true}，否则为 false。</li>
 * </ul>
 */
class OrchestrationTraceEmitTest {

    private static final int MAX_ROUNDS = 2;

    @Test
    @DisplayName("链路结束发布 OrchestrationTracedEvent：LLM 轮次与工具 span 起止/偏移/耗时自洽")
    void shouldEmitTraceWithSelfConsistentSpans() {
        RecordingPublisher publisher = new RecordingPublisher();
        StubLlmClient llm = new StubLlmClient(List.of(
                new ChatResult(null, List.of(ToolCall.function("c1", "query_express", "{}")),
                        null, "tool_calls", null),
                ChatResult.text("已为你查询，结果如下。")));
        AgentOrchestratorImpl orchestrator = build(publisher, 25000, 0L, llm);

        OrchestrationResult result = orchestrator.run(
                new OrchestrationRequest("trace-t6", "openid-t6-full", 9L, "帮我查快递", List.of()));

        assertThat(result.replyText()).contains("查询");
        List<OrchestrationTracedEvent> traces = publisher.traces();
        assertThat(traces).hasSize(1);
        OrchestrationTracedEvent event = traces.get(0);

        assertThat(event.traceId()).isEqualTo("trace-t6");
        // openid 脱敏（BR-21）
        assertThat(event.openid()).isEqualTo(MaskUtils.openid("openid-t6-full"));
        assertThat(event.openid()).doesNotContain("openid-t6-full");
        assertThat(event.totalBudgetMs()).isEqualTo(25000);
        assertThat(event.rounds()).isEqualTo(1);
        assertThat(event.exceededBudget()).isFalse();

        List<OrchestrationSpan> spans = event.spans();
        assertThat(spans).hasSize(3);

        // 顺序与类型
        assertThat(spans.get(0).kind()).isEqualTo(OrchestrationSpan.SpanKind.LLM_ROUND);
        assertThat(spans.get(1).kind()).isEqualTo(OrchestrationSpan.SpanKind.TOOL);
        assertThat(spans.get(2).kind()).isEqualTo(OrchestrationSpan.SpanKind.LLM_ROUND);
        // seq 从 1 递增
        assertThat(spans).extracting(OrchestrationSpan::seq).containsExactly(1, 2, 3);
        // 命名：LLM 用 "LLM#round"，工具用工具名
        assertThat(spans).extracting(OrchestrationSpan::name)
                .containsExactly("LLM#0", "query_express", "LLM#1");
        // 轮次：工具属于第 0 轮；第二个 LLM 属于第 1 轮
        assertThat(spans).extracting(OrchestrationSpan::round).containsExactly(0, 0, 1);
        // 状态：全部成功
        assertThat(spans).extracting(OrchestrationSpan::status)
                .containsOnly(OrchestrationSpan.SpanStatus.OK);

        // 起止自洽：偏移非负且单调不减；耗时非负；end <= totalMs
        long prevOffset = -1L;
        for (OrchestrationSpan span : spans) {
            assertThat(span.startOffsetMs()).isGreaterThanOrEqualTo(0L);
            assertThat(span.startOffsetMs()).isGreaterThanOrEqualTo(prevOffset);
            assertThat(span.durationMs()).isGreaterThanOrEqualTo(0L);
            assertThat(span.startOffsetMs() + span.durationMs()).isLessThanOrEqualTo(event.totalMs());
            prevOffset = span.startOffsetMs();
        }
    }

    @Test
    @DisplayName("链路耗时超过总预算 → exceededBudget=true；预算充裕 → false")
    void shouldFlagExceededBudget() {
        // 预算 1ms + 工具耗时 30ms → 必然超预算
        RecordingPublisher over = new RecordingPublisher();
        StubLlmClient slowLlm = new StubLlmClient(List.of(
                new ChatResult(null, List.of(ToolCall.function("c1", "query_express", "{}")),
                        null, "tool_calls", null),
                ChatResult.text("完成。")));
        AgentOrchestratorImpl overOrchestrator = build(over, 1, 30L, slowLlm);
        overOrchestrator.run(new OrchestrationRequest("trace-over", "openid-over-xyz", 1L, "查", List.of()));

        assertThat(over.traces()).hasSize(1);
        assertThat(over.traces().get(0).exceededBudget()).isTrue();
        assertThat(over.traces().get(0).totalMs()).isGreaterThanOrEqualTo(30L);
        assertThat(over.traces().get(0).totalBudgetMs()).isEqualTo(1);

        // 预算充裕（25s）→ 不超预算
        RecordingPublisher within = new RecordingPublisher();
        AgentOrchestratorImpl withinOrchestrator = build(within, 25000, 0L);
        withinOrchestrator.run(new OrchestrationRequest("trace-within", "openid-within-xyz", 1L, "查", List.of()));

        assertThat(within.traces()).hasSize(1);
        assertThat(within.traces().get(0).exceededBudget()).isFalse();
    }

    private AgentOrchestratorImpl build(ApplicationEventPublisher publisher, int totalBudgetMs, long toolDelayMs) {
        return build(publisher, totalBudgetMs, toolDelayMs, defaultLlm());
    }

    private AgentOrchestratorImpl build(ApplicationEventPublisher publisher, int totalBudgetMs, long toolDelayMs,
                                        LlmClient llm) {
        OrchestrationProperties orchestration = new OrchestrationProperties(
                MAX_ROUNDS, 3, totalBudgetMs, 8000, 1, Set.of());
        LlmProperties llmProperties = new LlmProperties("mock", "", "", "mock-model", "", 15, 20, 8000, 1000);
        return new AgentOrchestratorImpl(llm,
                new ToolRegistry(List.of(new TestTool("query_express", false, toolDelayMs))),
                new InMemoryContextStore(), new ContextTrimmer(new HeuristicTokenEstimator()),
                PASS_CHECKER, PASS_SAFETY, new DefaultFallbackService(), new NoOpToolCallLogService(),
                orchestration, llmProperties, null, null, null, publisher, null);
    }

    private static LlmClient defaultLlm() {
        return new StubLlmClient(List.of(ChatResult.text("你好呀。")));
    }

    /** 记录全部发布事件的事件发布器（避免 Mockito 类型捕获歧义）。 */
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

        List<OrchestrationTracedEvent> traces() {
            return events.stream()
                    .filter(OrchestrationTracedEvent.class::isInstance)
                    .map(OrchestrationTracedEvent.class::cast)
                    .toList();
        }
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

    private static final ConsistencyChecker PASS_CHECKER = (reply, executed) -> ConsistencyVerdict.pass();

    private static final ContentSafetyService PASS_SAFETY = text -> SafetyVerdict.pass();
}
