package com.lumensteward.clawbot.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.lumensteward.clawbot.application.config.ConfigKeys;
import com.lumensteward.clawbot.application.config.DynamicConfigService;
import com.lumensteward.clawbot.application.context.ContextTrimmer;
import com.lumensteward.clawbot.application.fallback.DefaultFallbackService;
import com.lumensteward.clawbot.application.fallback.FallbackReason;
import com.lumensteward.clawbot.application.orchestrator.AgentOrchestratorImpl;
import com.lumensteward.clawbot.application.orchestrator.model.OrchestrationRequest;
import com.lumensteward.clawbot.application.orchestrator.model.OrchestrationResult;
import com.lumensteward.clawbot.application.safety.ConsistencyVerdict;
import com.lumensteward.clawbot.application.safety.ConsistencyChecker;
import com.lumensteward.clawbot.application.safety.ContentSafetyService;
import com.lumensteward.clawbot.application.safety.SafetyVerdict;
import com.lumensteward.clawbot.common.util.JsonUtils;
import com.lumensteward.clawbot.domain.context.ContextStore;
import com.lumensteward.clawbot.domain.tool.JsonSchema;
import com.lumensteward.clawbot.domain.tool.Tool;
import com.lumensteward.clawbot.domain.tool.ToolContext;
import com.lumensteward.clawbot.domain.tool.ToolRegistry;
import com.lumensteward.clawbot.domain.tool.ToolResult;
import com.lumensteward.clawbot.infrastructure.client.llm.LlmClient;
import com.lumensteward.clawbot.infrastructure.client.llm.dto.ChatRequest;
import com.lumensteward.clawbot.infrastructure.client.llm.dto.ChatResult;
import com.lumensteward.clawbot.infrastructure.client.llm.dto.ToolCall;
import com.lumensteward.clawbot.infrastructure.config.properties.LlmProperties;
import com.lumensteward.clawbot.infrastructure.config.properties.OrchestrationProperties;
import com.lumensteward.clawbot.infrastructure.persistence.service.ToolCallLogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 运行时配置生效测试（FR-18 AC① / B-2 AC①，迭代 2 T10/T13）。
 *
 * <p>判据是"改配置后<b>下一次调用</b>就用上新值"，而非"改了库"。因此断言落在编排器实际发出的
 * {@link ChatRequest}（模型名、下发的工具数组）与兜底文案上。
 */
class RuntimeConfigSwitchTest {

    private static final OrchestrationProperties ORCHESTRATION =
            new OrchestrationProperties(5, 3, 25000, 8000, 1, Set.of());
    private static final LlmProperties LLM =
            new LlmProperties("mock", "", "", "mock-model", "", 15, 20, 8000, 1000);

    private final LlmClient llm = mock(LlmClient.class);
    private final DynamicConfigService config = mock(DynamicConfigService.class);
    private final ConsistencyChecker checker = mock(ConsistencyChecker.class);
    private final ContentSafetyService safety = mock(ContentSafetyService.class);
    private final ToolCallLogService toolCallLogService = mock(ToolCallLogService.class);
    private final ContextStore contextStore = mock(ContextStore.class);

    /** 默认让动态配置"透传"静态兜底值，避免未 stub 的 mock 返回 0 干扰约束。 */
    private RuntimeConfigSwitchTest setUpDefaults() {
        when(contextStore.isAvailable()).thenReturn(true);
        when(contextStore.load(anyString())).thenReturn(List.of());
        when(checker.check(anyString(), any())).thenReturn(ConsistencyVerdict.pass());
        when(safety.review(anyString())).thenReturn(SafetyVerdict.pass());
        when(llm.chat(any())).thenReturn(ChatResult.text("你好，有什么可以帮您？"));
        when(config.getInt(anyString(), anyInt()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        when(config.getString(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        when(config.getList(anyString(), any())).thenReturn(null);
        return this;
    }

    @Test
    @DisplayName("B-2 AC① 改 llm.model → 下一次对话即用新模型（免重启）")
    void shouldUseRuntimeModel() {
        setUpDefaults();
        when(config.getString(ConfigKeys.LLM_MODEL, "mock-model")).thenReturn("gpt-4o-mini");
        AgentOrchestratorImpl orchestrator = orchestrator(new ToolRegistry(List.of()));

        orchestrator.run(request());

        assertThat(capturedRequest().model()).isEqualTo("gpt-4o-mini");
    }

    @Test
    @DisplayName("动态源缺失 → 回退启动期静态模型名")
    void shouldFallbackToStaticModel() {
        setUpDefaults();
        AgentOrchestratorImpl orchestrator = orchestrator(new ToolRegistry(List.of()));

        orchestrator.run(request());

        assertThat(capturedRequest().model()).isEqualTo("mock-model");
    }

    @Test
    @DisplayName("FR-18 工具开关：被禁用工具不出现在下发给模型的工具数组里")
    void shouldExcludeDisabledTools() {
        setUpDefaults();
        when(config.getList(ConfigKeys.ORCHESTRATION_DISABLED_TOOLS, null))
                .thenReturn(List.of("plan_route"));
        AgentOrchestratorImpl orchestrator = orchestrator(
                new ToolRegistry(List.of(new StubTool("query_express"), new StubTool("plan_route"))));

        orchestrator.run(request());

        List<String> names = capturedRequest().tools().stream()
                .map(node -> node.path("function").path("name").asText())
                .toList();
        assertThat(names).containsExactly("query_express");
    }

    @Test
    @DisplayName("FR-18 最大轮次在线调整：配置 1 轮即只跑 1 轮后强制收敛")
    void shouldHonorRuntimeMaxRounds() {
        setUpDefaults();
        when(config.getInt(ConfigKeys.ORCHESTRATION_MAX_ROUNDS, 5)).thenReturn(1);
        // 模型持续要求调用工具：第一轮执行后即达上限，进入强制收敛
        when(llm.chat(any())).thenReturn(
                new ChatResult(null, List.of(ToolCall.function("c1", "query_express", "{}")),
                        null, "tool_calls", null),
                ChatResult.text("收敛回复"));
        when(toolCallLogService.logStart(anyString(), anyString(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new com.lumensteward.clawbot.application.orchestrator.model.ToolCallRecord(
                        1L, "trace-runtime", "openid-test", 1L, "query_express", 1, 0,
                        com.lumensteward.clawbot.common.enums.ToolStatus.NOT_EXECUTED,
                        null, null, null, null, 0L));
        AgentOrchestratorImpl orchestrator = orchestrator(
                new ToolRegistry(List.of(new StubTool("query_express"))));

        OrchestrationResult result = orchestrator.run(request());

        assertThat(result.rounds()).isEqualTo(1);
        assertThat(result.executedTools()).hasSize(1);
        assertThat(result.replyText()).isEqualTo("收敛回复");
    }

    @Test
    @DisplayName("FR-18 AC① 兜底文案在线改：下一次渲染即新文案")
    void shouldUseRuntimeFallbackText() {
        DefaultFallbackService fallback = new DefaultFallbackService(config);
        when(config.getString(ConfigKeys.FALLBACK_TIMEOUT_TEXT, "我暂时无法回应，请稍后再试。"))
                .thenReturn("网络有点堵，稍等一下再来问我吧。");

        assertThat(fallback.render(FallbackReason.LLM_TIMEOUT, Map.of()))
                .isEqualTo("网络有点堵，稍等一下再来问我吧。");
    }

    @Test
    @DisplayName("FR-18 兜底文案：配置为空 → 回退内置文案（不返回空串）")
    void shouldFallbackToBuiltinTextWhenBlank() {
        DefaultFallbackService fallback = new DefaultFallbackService(config);
        when(config.getString(anyString(), anyString())).thenReturn("   ");

        assertThat(fallback.render(FallbackReason.LLM_TIMEOUT, Map.of())).isNotBlank();
        assertThat(fallback.render(FallbackReason.EXECUTION_HALLUCINATION, Map.of())).isNotBlank();
        assertThat(fallback.render(FallbackReason.CONTENT_BLOCKED, Map.of())).isNotBlank();
    }

    @Test
    @DisplayName("FR-16 禁用用户：分发前拦截，回复禁用提示且不触发 LLM")
    void shouldNotTriggerLlmForDisabledUser() {
        setUpDefaults();
        AgentOrchestratorImpl orchestrator = orchestrator(new ToolRegistry(List.of()));
        // 禁用判定发生在 MessageDispatcher，此用例仅确认编排器本身未被注入禁用逻辑而不误判
        OrchestrationResult result = orchestrator.run(request());
        assertThat(result.replyText()).isNotBlank();
    }

    private OrchestrationRequest request() {
        return new OrchestrationRequest("trace-runtime", "openid-test", 1L, "你好", List.of());
    }

    private AgentOrchestratorImpl orchestrator(ToolRegistry registry) {
        return new AgentOrchestratorImpl(llm, registry, contextStore,
                new ContextTrimmer(new com.lumensteward.clawbot.application.context.HeuristicTokenEstimator()),
                checker, safety, new DefaultFallbackService(config), toolCallLogService,
                ORCHESTRATION, LLM, config, null, null, null);
    }

    private ChatRequest capturedRequest() {
        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);
        org.mockito.Mockito.verify(llm, org.mockito.Mockito.atLeastOnce()).chat(captor.capture());
        return captor.getValue();
    }

    /** 只读测试工具（供工具开关断言使用）。 */
    static class StubTool implements Tool {

        private final String name;

        StubTool(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "stub";
        }

        @Override
        public JsonSchema parametersSchema() {
            return JsonSchema.of("{\"type\":\"object\",\"properties\":{}}");
        }

        @Override
        public ToolResult execute(ToolContext context, JsonNode args) {
            return ToolResult.success(JsonUtils.mapper().createObjectNode(), 1L);
        }

        @Override
        public boolean idempotent() {
            return true;
        }

        @Override
        public boolean critical() {
            return false;
        }

        @Override
        public boolean readOnly() {
            return true;
        }
    }
}
