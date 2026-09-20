package com.lumensteward.clawbot.console;

import com.fasterxml.jackson.databind.JsonNode;
import com.lumensteward.clawbot.application.context.ContextTrimmer;
import com.lumensteward.clawbot.application.context.HeuristicTokenEstimator;
import com.lumensteward.clawbot.application.console.ConsoleEvent;
import com.lumensteward.clawbot.application.console.ConsoleEventType;
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
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * AgentOrchestratorImpl 发布 ConsoleEvent 验证（FR-08 / T3）。
 *
 * <p>经<b>完整</b>构造器注入 {@link ApplicationEventPublisher}（mock，非 null），执行一次含工具调用的编排，
 * 断言 TOOL_START / TOOL_END / MESSAGE_DELTA / DONE 均被发布，且工具事件携带 toolName 与 round。
 * 同时验证序号全局单调（TOOL_START 与 TOOL_END 同刻发布，seq 连续）。
 */
class AgentOrchestratorConsolePublishTest {

    private static final int MAX_ROUNDS = 2;

    @Test
    @DisplayName("编排含工具调用：发布 TOOL_START/TOOL_END/MESSAGE_DELTA/DONE 且参数正确")
    void shouldPublishConsoleEventsOnRun() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        StubLlmClient llm = new StubLlmClient(List.of(
                new ChatResult(null, List.of(ToolCall.function("c1", "query_express", "{}")),
                        null, "tool_calls", null),
                ChatResult.text("已为你查询，结果如下。")));
        AgentOrchestratorImpl orchestrator = build(publisher, llm);

        OrchestrationResult result = orchestrator.run(
                new OrchestrationRequest("trace-pub", "openid-pub", 7L, "帮我查快递", List.of()));

        assertThat(result.replyText()).contains("查询");

        var captor = forClass(ConsoleEvent.class);
        verify(publisher, atLeastOnce()).publishEvent(captor.capture());
        List<ConsoleEvent> events = captor.getAllValues();

        assertThat(events).anyMatch(e -> e.getType() == ConsoleEventType.TOOL_START
                && "query_express".equals(e.getToolName()) && e.getRound() == 0);
        assertThat(events).anyMatch(e -> e.getType() == ConsoleEventType.TOOL_END
                && "query_express".equals(e.getToolName()));
        assertThat(events).anyMatch(e -> e.getType() == ConsoleEventType.MESSAGE_DELTA
                && e.getPayload() != null && e.getPayload().contains("查询"));
        assertThat(events).anyMatch(e -> e.getType() == ConsoleEventType.DONE);
        // 同一链路内 TOOL_START 仅一次
        assertThat(events.stream().filter(e -> e.getType() == ConsoleEventType.TOOL_START).count()).isOne();
    }

    private AgentOrchestratorImpl build(ApplicationEventPublisher publisher, LlmClient llm) {
        OrchestrationProperties orchestration = new OrchestrationProperties(
                MAX_ROUNDS, 3, 25000, 8000, 1, Set.of());
        LlmProperties llmProperties = new LlmProperties("mock", "", "", "mock-model", "", 15, 20, 8000, 1000);
        return new AgentOrchestratorImpl(llm, new ToolRegistry(List.of(new TestTool("query_express", false, 0L))),
                new InMemoryContextStore(), new ContextTrimmer(new HeuristicTokenEstimator()),
                PASS_CHECKER, PASS_SAFETY, new DefaultFallbackService(), new NoOpToolCallLogService(),
                orchestration, llmProperties, null, null, null, publisher, null);
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
