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
import com.lumensteward.clawbot.application.task.RuleBasedSlotFiller;
import com.lumensteward.clawbot.application.task.TaskSessionServiceImpl;
import com.lumensteward.clawbot.common.enums.ToolStatus;
import com.lumensteward.clawbot.domain.context.ContextStore;
import com.lumensteward.clawbot.domain.intent.IntentClassifier;
import com.lumensteward.clawbot.domain.intent.IntentResult;
import com.lumensteward.clawbot.domain.model.TaskContext;
import com.lumensteward.clawbot.domain.port.ExpressQueryPort;
import com.lumensteward.clawbot.domain.port.model.ExpressTrace;
import com.lumensteward.clawbot.domain.task.TaskStore;
import com.lumensteward.clawbot.domain.tool.ToolRegistry;
import com.lumensteward.clawbot.domain.tool.ToolResult;
import com.lumensteward.clawbot.domain.tool.impl.QueryExpressTool;
import com.lumensteward.clawbot.infrastructure.client.llm.LlmClient;
import com.lumensteward.clawbot.infrastructure.client.llm.dto.ChatMessage;
import com.lumensteward.clawbot.infrastructure.client.llm.dto.ChatRequest;
import com.lumensteward.clawbot.infrastructure.client.llm.dto.ChatResult;
import com.lumensteward.clawbot.infrastructure.client.llm.dto.ToolCall;
import com.lumensteward.clawbot.domain.port.model.VisionRequest;
import com.lumensteward.clawbot.domain.port.model.VisionResult;
import com.lumensteward.clawbot.infrastructure.config.properties.LlmProperties;
import com.lumensteward.clawbot.infrastructure.config.properties.OrchestrationProperties;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.WxSessionMapper;
import com.lumensteward.clawbot.infrastructure.persistence.service.ToolCallLogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 任务型多步会话端到端编排测试（SRS FR-24 验收①）。
 *
 * <p>以脚本化 Mock LLM 复现：首轮「快递缺单号」→ 编排器建立任务（状态 TASKING）；
 * 次轮用户<b>仅回复单号</b>→ 槽位填充命中并续接 → （Mock）执行快递查询工具 → 产出结果。
 * 任务活跃态以内存 {@link TaskStore} 替身承载（Redis 键行为另见 RedisTaskStoreTest）。
 */
class TaskSessionOrchestrationIntegrationTest {

    private static final String OPENID = "openid-fr24-e2e";
    private static final Long SESSION_ID = 5L;

    private final InMemoryTaskStore taskStore = new InMemoryTaskStore();

    @Test
    @DisplayName("① 缺单号追问→仅回复单号→续接并执行查询")
    void shouldAskThenResumeAndExecuteAfterUserSuppliesTrackingNo() {
        StubLlmClient llm = new StubLlmClient(List.of(
                // 首轮：模型调用 query_express 但缺 tracking_no（触发 Schema 校验失败 → 追问）
                new ChatResult(null, List.of(ToolCall.function("c1", "query_express", "{\"company_code\":\"SF\"}")),
                        null, "tool_calls", null),
                ChatResult.text("请把你的快递单号发给我，我来帮你查～"),
                // 次轮：用户仅回复单号 → 槽位齐备续接后，模型携带参数再次调用
                new ChatResult(null, List.of(ToolCall.function("c2", "query_express",
                        "{\"tracking_no\":\"SF1234567890\"}")), null, "tool_calls", null),
                ChatResult.text("你的快递正在运输中，预计明天送达～")));
        AgentOrchestratorImpl orchestrator = buildOrchestrator(llm);

        // 首轮：缺单号 → 建立任务
        OrchestrationResult first = orchestrator.run(
                new OrchestrationRequest("trace-1", OPENID, SESSION_ID, "我的快递到哪了", List.of()));
        assertThat(first.replyText()).contains("快递单号");
        List<TaskContext> created = taskStore.load(OPENID).orElseThrow();
        assertThat(created).isNotEmpty();
        assertThat(created.get(0).taskType()).isEqualTo("query_express");
        assertThat(created.get(0).requiredSlots()).containsExactly("tracking_no");

        // 次轮：仅回复单号 → 续接并执行查询
        OrchestrationResult second = orchestrator.run(
                new OrchestrationRequest("trace-2", OPENID, SESSION_ID, "SF1234567890", List.of()));
        assertThat(second.replyText()).contains("运输中");
        boolean executedExpress = second.executedTools().stream()
                .anyMatch(r -> "query_express".equals(r.toolName()) && r.status() == ToolStatus.SUCCESS);
        assertThat(executedExpress).isTrue();
        // 续接完成后任务清理
        assertThat(taskStore.exists(OPENID)).isFalse();
    }

    private AgentOrchestratorImpl buildOrchestrator(LlmClient llm) {
        OrchestrationProperties orchestration = new OrchestrationProperties(3, 3, 25000, 8000, 1, Set.of());
        LlmProperties llmProperties = new LlmProperties("mock", "", "", "mock-model", "", 15, 20, 8000, 1000);

        ExpressQueryPort port = (companyCode, trackingNo) -> new ExpressTrace(companyCode, trackingNo,
                "运输中", true, List.of(new ExpressTrace.Node("2025-01-01 10:00", "已揽收", "深圳")));
        ToolRegistry registry = new ToolRegistry(List.of(new QueryExpressTool(port)));

        IntentClassifier classifier = (history, message) -> IntentResult.unknown();
        TaskSessionServiceImpl taskService = new TaskSessionServiceImpl(
                taskStore, mock(WxSessionMapper.class), new RuleBasedSlotFiller(), classifier, null, registry);

        return new AgentOrchestratorImpl(llm, registry, new InMemoryContextStore(),
                new ContextTrimmer(new HeuristicTokenEstimator()), PASS_CHECKER, PASS_SAFETY,
                new DefaultFallbackService(), new NoOpToolCallLogService(),
                orchestration, llmProperties, null, null, null, null, null, taskService, null);
    }

    private static final ConsistencyChecker PASS_CHECKER = (reply, executed) -> ConsistencyVerdict.pass();
    private static final ContentSafetyService PASS_SAFETY = text -> SafetyVerdict.pass();

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

    /** 空操作工具日志。 */
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

    /** 内存任务栈。 */
    static class InMemoryTaskStore implements TaskStore {
        private final Map<String, List<TaskContext>> data = new HashMap<>();

        @Override
        public boolean save(String openid, List<TaskContext> tasks, Duration ttl) {
            data.put(openid, new ArrayList<>(tasks));
            return true;
        }

        @Override
        public Optional<List<TaskContext>> load(String openid) {
            List<TaskContext> value = data.get(openid);
            return value == null || value.isEmpty() ? Optional.empty() : Optional.of(new ArrayList<>(value));
        }

        @Override
        public void delete(String openid) {
            data.remove(openid);
        }

        @Override
        public boolean exists(String openid) {
            List<TaskContext> value = data.get(openid);
            return value != null && !value.isEmpty();
        }
    }
}
