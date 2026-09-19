package com.lumensteward.clawbot.verification;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.lumensteward.clawbot.application.context.ContextTrimmer;
import com.lumensteward.clawbot.application.context.HeuristicTokenEstimator;
import com.lumensteward.clawbot.application.fallback.DefaultFallbackService;
import com.lumensteward.clawbot.application.orchestrator.AgentOrchestratorImpl;
import com.lumensteward.clawbot.application.orchestrator.model.OrchestrationRequest;
import com.lumensteward.clawbot.application.orchestrator.model.OrchestrationResult;
import com.lumensteward.clawbot.application.orchestrator.model.ToolCallRecord;
import com.lumensteward.clawbot.application.safety.ActionClaimExtractor;
import com.lumensteward.clawbot.application.safety.ConsistencyChecker;
import com.lumensteward.clawbot.application.safety.ContentSafetyService;
import com.lumensteward.clawbot.application.safety.RuleBasedConsistencyChecker;
import com.lumensteward.clawbot.application.safety.SafetyVerdict;
import com.lumensteward.clawbot.common.enums.ToolStatus;
import com.lumensteward.clawbot.domain.context.ContextStore;
import com.lumensteward.clawbot.domain.tool.ToolRegistry;
import com.lumensteward.clawbot.infrastructure.client.llm.LlmClient;
import com.lumensteward.clawbot.infrastructure.client.llm.dto.ChatMessage;
import com.lumensteward.clawbot.infrastructure.client.llm.dto.ChatRequest;
import com.lumensteward.clawbot.infrastructure.client.llm.dto.ChatResult;
import com.lumensteward.clawbot.infrastructure.client.llm.dto.ToolCall;
import com.lumensteward.clawbot.domain.port.model.VisionRequest;
import com.lumensteward.clawbot.domain.port.model.VisionResult;
import com.lumensteward.clawbot.infrastructure.config.properties.LlmProperties;
import com.lumensteward.clawbot.infrastructure.config.properties.OrchestrationProperties;
import com.lumensteward.clawbot.infrastructure.config.properties.SafetyProperties;
import com.lumensteward.clawbot.infrastructure.observability.TraceContext;
import com.lumensteward.clawbot.infrastructure.persistence.service.ToolCallLogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 执行性泄漏（Leak）场景验证（SRS 9.4.5 / BR-04 / FR-09 收尾）。
 *
 * <p>覆盖：模型在<b>未真正调用任何工具</b>的情况下，于终态文本中声称已执行某动作
 * （含本次迭代新增的「识别」「生成」动词）。编排器的一致性校验必须整条拦截并降级为
 * {@code EXECUTION_HALLUCINATION}，且拦截日志须携带链路 {@code traceId}（MDC）。
 *
 * <p>用例数 ≥ 20，枚举完成态动词词表，确保反幻觉铁律不被任一动词绕过。
 */
class ExecutionLeakScenarioVerificationTest {

    private static final String TRACE_ID = "leak-trace-0001";

    private ListAppender<ILoggingEvent> appender;

    static List<String> leakReplies() {
        return List.of(
                "我已为你查询到物流：包裹已发出。",
                "我已查到您的快递位置。",
                "我已查找并定位了您的包裹。",
                "我已获取您的宠物档案信息。",
                "我已规划好去公司的路线。",
                "我已为你保存好宠物档案。",
                "我已经记下狗狗的疫苗记录。",
                "我已记下您说的注意事项。",
                "我已更新猫咪的体重数据。",
                "我已修改豆豆的生日信息。",
                "我已删除旧档案。",
                "我已删掉那条记录。",
                "我已登记新宠物。",
                "我已发送语音消息给您。",
                "我已合成一段语音。",
                "我已生成语音回复。",
                "我已找到附近的药店。",
                "我已定位您的当前位置。",
                "我已设置每日提醒。",
                "我已添加一项待办。",
                "我已新增一条备忘。",
                "我已识别这只猫的品种。",
                "我已为你识别图片中的物体。",
                "我已生成路线导航链接。");
    }

    @BeforeEach
    void setUp() {
        TraceContext.setTraceId(TRACE_ID);
        Logger orchestratorLogger = (Logger) LoggerFactory.getLogger(AgentOrchestratorImpl.class);
        appender = new ListAppender<>();
        appender.start();
        orchestratorLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        Logger orchestratorLogger = (Logger) LoggerFactory.getLogger(AgentOrchestratorImpl.class);
        orchestratorLogger.detachAppender(appender);
        appender.stop();
        TraceContext.clear();
    }

    @ParameterizedTest(name = "泄漏场景[{index}]：{0}")
    @MethodSource("leakReplies")
    @DisplayName("FR-09：模型声称已执行但无工具记录 → 拦截为 EXECUTION_HALLUCINATION 且日志含 traceId")
    void leakClaimMustBeIntercepted(String reply) {
        StubLlmClient llm = new StubLlmClient(List.of(ChatResult.text(reply)));
        AgentOrchestratorImpl orchestrator = buildOrchestrator(llm);

        OrchestrationResult result = orchestrator.run(request());

        assertThat(result.fallbackReason())
                .as("一致性校验必须拦截幻觉声明")
                .isEqualTo("EXECUTION_HALLUCINATION");
        assertLogCarriesTraceId();
    }

    @Test
    @DisplayName("FR-09：纯闲聊（无动作声明）→ 不触发 EXECUTION_HALLUCINATION")
    void chitchatShouldNotBeIntercepted() {
        StubLlmClient llm = new StubLlmClient(List.of(ChatResult.text("今天天气不错，你最近好吗？")));
        AgentOrchestratorImpl orchestrator = buildOrchestrator(llm);

        OrchestrationResult result = orchestrator.run(request());

        assertThat(result.fallbackReason())
                .as("闲聊不应被识别为执行性幻觉")
                .isNotEqualTo("EXECUTION_HALLUCINATION");
    }

    private void assertLogCarriesTraceId() {
        boolean found = appender.list.stream()
                .anyMatch(event -> TRACE_ID.equals(event.getMDCPropertyMap().get(TraceContext.TRACE_ID)));
        assertThat(found)
                .as("拦截日志必须携带链路 traceId（MDC）以便追溯")
                .isTrue();
    }

    private OrchestrationRequest request() {
        return new OrchestrationRequest(TRACE_ID, "openid-leak", 1L, "帮我处理一下", List.of());
    }

    private AgentOrchestratorImpl buildOrchestrator(LlmClient llm) {
        OrchestrationProperties orchestration = new OrchestrationProperties(2, 3, 25000, 8000, 1, Set.of());
        LlmProperties llmProperties = new LlmProperties("mock", "", "", "mock-model", "", 15, 20, 8000, 1000);
        SafetyProperties safety = new SafetyProperties("classpath:none/x.txt", true, false);
        ConsistencyChecker checker = new RuleBasedConsistencyChecker(new ActionClaimExtractor(), safety);
        ContentSafetyService passSafety = text -> SafetyVerdict.pass();
        return new AgentOrchestratorImpl(llm, new ToolRegistry(List.of()),
                new InMemoryContextStore(), new ContextTrimmer(new HeuristicTokenEstimator()),
                checker, passSafety, new DefaultFallbackService(),
                new NoOpToolCallLogService(), orchestration, llmProperties, null, null);
    }

    /** 顺序返回预置响应的 LLM 客户端桩。 */
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

    /** 内存上下文桩。 */
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

    /** 空操作的同步日志服务桩（不落库）。 */
    static class NoOpToolCallLogService implements ToolCallLogService {
        @Override
        public ToolCallRecord logStart(String traceId, String openid, Long sessionId, ToolCall call,
                                       int round, int callSeq) {
            return new ToolCallRecord(1L, traceId, openid, sessionId,
                    call == null ? null : call.functionName(), callSeq, round, ToolStatus.NOT_EXECUTED,
                    null, null, null, null, 0L);
        }

        @Override
        public void logEnd(ToolCallRecord record, com.lumensteward.clawbot.domain.tool.ToolResult result) {
            // 测试：不落库
        }
    }
}
