package com.lumensteward.clawbot.application.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.lumensteward.clawbot.application.context.ContextTrimmer;
import com.lumensteward.clawbot.application.fallback.FallbackReason;
import com.lumensteward.clawbot.application.fallback.FallbackService;
import com.lumensteward.clawbot.application.orchestrator.model.OrchestrationRequest;
import com.lumensteward.clawbot.application.orchestrator.model.OrchestrationResult;
import com.lumensteward.clawbot.application.orchestrator.model.ToolCallRecord;
import com.lumensteward.clawbot.application.safety.ConsistencyChecker;
import com.lumensteward.clawbot.application.safety.ConsistencyVerdict;
import com.lumensteward.clawbot.application.safety.ContentSafetyService;
import com.lumensteward.clawbot.application.safety.RuleBasedConsistencyChecker;
import com.lumensteward.clawbot.application.safety.SafetyVerdict;
import com.lumensteward.clawbot.common.enums.SessionState;
import com.lumensteward.clawbot.common.enums.ToolStatus;
import com.lumensteward.clawbot.common.util.JsonUtils;
import com.lumensteward.clawbot.common.util.MaskUtils;
import com.lumensteward.clawbot.domain.context.ContextStore;
import com.lumensteward.clawbot.domain.tool.Tool;
import com.lumensteward.clawbot.domain.tool.ToolContext;
import com.lumensteward.clawbot.domain.tool.ToolRegistry;
import com.lumensteward.clawbot.domain.tool.ToolResult;
import com.lumensteward.clawbot.domain.tool.ValidationResult;
import com.lumensteward.clawbot.infrastructure.client.llm.LlmClient;
import com.lumensteward.clawbot.infrastructure.client.llm.dto.ChatMessage;
import com.lumensteward.clawbot.infrastructure.client.llm.dto.ChatRequest;
import com.lumensteward.clawbot.infrastructure.client.llm.dto.ChatResult;
import com.lumensteward.clawbot.infrastructure.client.llm.dto.ToolCall;
import com.lumensteward.clawbot.infrastructure.client.llm.exception.LlmException;
import com.lumensteward.clawbot.infrastructure.config.properties.LlmProperties;
import com.lumensteward.clawbot.infrastructure.config.properties.OrchestrationProperties;
import com.lumensteward.clawbot.infrastructure.observability.TraceContext;
import com.lumensteward.clawbot.infrastructure.persistence.service.ToolCallLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Agent 编排器实现（架构 5.2 / SRS 9.4.3 伪代码落地）。
 *
 * <p>约束：SC-01（≤5 轮，第 6 次调用为强制收敛）、SC-02（单轮 ≤3 并行工具，超出截断）、
 * SC-03（总预算 25s、单工具 8s）、SC-04（达轮次上限强制收敛）、SC-05（关键工具失败/超时中断）。
 * 输出治理：内容安全（Fail-Closed）→ 执行一致性校验（DETECTED 整条拦截）。
 *
 * <p>构造器在 5.2 签名基础上<b>追加</b> {@code LlmProperties}：用于模型名与 token 预算
 * （FR-04 上下文裁剪主控所需）。
 */
@Service
public class AgentOrchestratorImpl implements AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestratorImpl.class);

    /** 系统提示词骨架（附录 C 的最小落地）。 */
    private static final String SYSTEM_PROMPT = """
            你是"衔光管家"，一个诚实、克制的微信助手。
            原则：能力边界内尽力协助；不确定或无法获取信息时如实说明，绝不编造结果；
            参数缺失时先追问，不猜测执行。回答简洁、口语化。""";

    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final ContextStore contextStore;
    private final ContextTrimmer contextTrimmer;
    private final ConsistencyChecker consistencyChecker;
    private final ContentSafetyService contentSafetyService;
    private final FallbackService fallbackService;
    private final ToolCallLogService toolCallLogService;
    private final OrchestrationProperties orchestrationProperties;
    private final LlmProperties llmProperties;

    /** 工具执行超时隔离线程池（daemon，避免阻塞 JVM 退出）。 */
    private final ExecutorService toolExecutor = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "clawbot-tool-exec");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * 构造器注入（G-14）。
     *
     * @param llmClient             LLM 客户端
     * @param toolRegistry          工具注册中心
     * @param contextStore          上下文存储
     * @param contextTrimmer        上下文裁剪器
     * @param consistencyChecker    执行一致性校验
     * @param contentSafetyService  内容安全
     * @param fallbackService       兜底文案
     * @param toolCallLogService    工具调用日志（同步）
     * @param orchestrationProperties 编排配置
     * @param llmProperties         LLM 配置（模型名与 token 预算）
     */
    public AgentOrchestratorImpl(LlmClient llmClient, ToolRegistry toolRegistry,
                                 ContextStore contextStore, ContextTrimmer contextTrimmer,
                                 ConsistencyChecker consistencyChecker,
                                 ContentSafetyService contentSafetyService,
                                 FallbackService fallbackService,
                                 ToolCallLogService toolCallLogService,
                                 OrchestrationProperties orchestrationProperties,
                                 LlmProperties llmProperties) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.contextStore = contextStore;
        this.contextTrimmer = contextTrimmer;
        this.consistencyChecker = consistencyChecker;
        this.contentSafetyService = contentSafetyService;
        this.fallbackService = fallbackService;
        this.toolCallLogService = toolCallLogService;
        this.orchestrationProperties = orchestrationProperties;
        this.llmProperties = llmProperties;
    }

    @Override
    public OrchestrationResult run(OrchestrationRequest request) {
        if (request == null || request.userMessage() == null || request.userMessage().isBlank()) {
            String text = fallbackService.render(FallbackReason.INVALID_ARGS, Map.of());
            return new OrchestrationResult(text, SessionState.IDLE, List.of(),
                    FallbackReason.INVALID_ARGS.name(), 0, 0);
        }
        String traceId = request.traceId() == null ? TraceContext.getTraceId() : request.traceId();
        String openid = request.openid();
        Long sessionId = request.sessionId();
        int maxRounds = Math.max(1, orchestrationProperties.maxRounds());
        int maxParallel = Math.max(1, orchestrationProperties.maxParallelTools());
        Duration llmTimeout = Duration.ofSeconds(Math.max(1, llmProperties.timeoutSeconds()));

        List<ToolCallRecord> executed = new ArrayList<>();
        int llmCalls = 0;
        int round = 0;
        // 链路内工具调用序号（AC-B6/B7：call_seq 按实际执行顺序从 1 递增）
        int[] callSeq = {0};

        // 上下文：优先使用请求携带的历史，否则从 ContextStore 载入（BR-07 按 openid 隔离）
        if (!contextStore.isAvailable()) {
            log.warn("上下文存储不可用，进入无状态降级（BR-07 / 9.5）");
        }
        List<ChatMessage> history = (request.history() == null || request.history().isEmpty())
                ? contextStore.load(openid) : request.history();
        List<ChatMessage> trimmed = contextTrimmer.trim(history, llmProperties.inputBudgetTokens(),
                llmProperties.reservedOutputTokens());

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(SYSTEM_PROMPT));
        messages.addAll(trimmed);
        messages.add(ChatMessage.user(request.userMessage()));

        List<JsonNode> tools = toolRegistry.enabledSchemas(orchestrationProperties.disabledTools());

        ChatResult response = null;
        while (round < maxRounds) {
            try {
                response = llmClient.chat(ChatRequest.of(effectiveModel(), messages, tools, llmTimeout));
                llmCalls++;
            } catch (LlmException e) {
                FallbackReason reason = mapLlmReason(e);
                log.warn("LLM 调用失败，走降级: errType={} reason={}", e.errorType(), reason);
                return fallback(request, reason, executed, llmCalls, round);
            }

            if (response == null || !response.hasToolCalls()) {
                break;
            }

            List<ToolCall> calls = response.toolCalls();
            if (calls.size() > maxParallel) {
                log.warn("单轮工具数 {} 超过 SC-02 上限 {}，截断", calls.size(), maxParallel);
                calls = new ArrayList<>(calls.subList(0, maxParallel));
            }
            messages.add(ChatMessage.assistantToolCalls(calls));

            for (ToolCall call : calls) {
                ToolOutcome outcome = executeOne(call, round, callSeq[0] + 1, traceId, openid, sessionId, executed);
                callSeq[0]++;
                messages.add(ChatMessage.tool(call.id(), call.functionName(), outcome.messageContent()));
                if (outcome.interrupt()) {
                    return fallback(request, outcome.interruptReason(), executed, llmCalls, round);
                }
            }
            round++;
        }

        // SC-04：达轮次上限 → 强制收敛（第 6 次调用）
        boolean forcedConvergence = round >= maxRounds;
        if (forcedConvergence) {
            messages.add(ChatMessage.system("请基于已有信息给出回复，并说明信息可能不完整。"));
            try {
                response = llmClient.chat(ChatRequest.of(effectiveModel(), messages, tools, llmTimeout));
                llmCalls++;
            } catch (LlmException e) {
                log.warn("强制收敛调用失败: errType={}", e.errorType());
                return fallback(request, FallbackReason.FORCED_CONVERGENCE, executed, llmCalls, round);
            }
        }

        String reply = response == null ? null : response.content();
        if (reply == null || reply.isBlank()) {
            // 无终态文本：诚实兜底（不编造）
            return fallback(request, FallbackReason.EMPTY_RESULT, executed, llmCalls, round);
        }

        // 输出治理 1：内容安全（Fail-Closed）
        SafetyVerdict safetyVerdict = contentSafetyService.review(reply);
        if (!safetyVerdict.passed()) {
            FallbackReason reason = safetyVerdict.serviceUnavailable()
                    ? FallbackReason.SAFETY_UNAVAILABLE : FallbackReason.CONTENT_BLOCKED;
            return fallback(request, reason, executed, llmCalls, round);
        }

        // 输出治理 2：执行一致性校验（保守：任一声明无支撑即整条拦截）
        ConsistencyVerdict consistencyVerdict = consistencyChecker.check(reply, executed);
        if (!consistencyVerdict.passed()) {
            log.warn("一致性校验拦截 reply（BR-04），reason={}", consistencyVerdict.reason());
            return fallback(request, FallbackReason.EXECUTION_HALLUCINATION, executed, llmCalls, round);
        }

        // 严格模式：存在失败工具调用时前置免责说明（9.4.5 异常流 3a）
        if (consistencyChecker instanceof RuleBasedConsistencyChecker ruleBased
                && ruleBased.strictMode() && hasUnsuccessful(executed)) {
            reply = "（提示：部分步骤未能完成）" + reply;
        }

        // 写回上下文（best-effort）
        contextStore.appendAll(openid, List.of(ChatMessage.user(request.userMessage()),
                ChatMessage.assistant(reply)));

        log.info("编排完成 openid={} rounds={} llmCalls={} tools={}",
                MaskUtils.openid(openid), round, llmCalls, executed.size());
        return new OrchestrationResult(reply, SessionState.TASKING, executed, null, llmCalls, round);
    }

    /**
     * 执行单个工具调用（含 Schema 校验、超时隔离、日志、SC-05 判定）。
     *
     * @param call      工具调用
     * @param round     当前轮次
     * @param callSeq   本次链路调用序号（从 1 递增，AC-B6/B7）
     * @param traceId   链路标识
     * @param openid    用户
     * @param sessionId 会话
     * @param executed  已执行记录（收集器）
     * @return 执行产物（含回注内容与是否中断）
     */
    private ToolOutcome executeOne(ToolCall call, int round, int callSeq, String traceId, String openid,
                                   Long sessionId, List<ToolCallRecord> executed) {
        String name = call.functionName();
        JsonNode args = JsonUtils.readTree(call.argumentsJson());

        // 工具未注册（模型编造工具名）：不执行，回注原因（9.4.3 第 25-29 行）
        java.util.Optional<Tool> toolOpt = toolRegistry.find(name);
        if (toolOpt.isEmpty()) {
            ToolResult result = ToolResult.notExecuted("TOOL_NOT_FOUND",
                    "工具未注册，可用工具: " + toolRegistry.names());
            recordNotExecuted(call, round, callSeq, traceId, openid, sessionId, executed, result);
            return new ToolOutcome(toToolContent(call.id(), name, result), false, null);
        }
        Tool tool = toolOpt.get();

        // Schema 校验（9.4.3 第 31-35 行）
        ValidationResult validation = tool.parametersSchema().validate(args == null ? JsonUtils.mapper().createObjectNode() : args);
        if (!validation.valid()) {
            ToolResult result = ToolResult.notExecuted("INVALID_ARGS",
                    "参数非法: " + validation.describe());
            recordNotExecuted(call, round, callSeq, traceId, openid, sessionId, executed, result);
            return new ToolOutcome(toToolContent(call.id(), name, result), false, null);
        }

        // 执行（同步日志：logStart → execute → logEnd，ADR-003）
        ToolCallRecord record = toolCallLogService.logStart(traceId, openid, sessionId, call, round, callSeq);
        long start = System.currentTimeMillis();
        ToolResult result = executeWithTimeout(tool, traceId, openid, sessionId, round, args);
        long latency = System.currentTimeMillis() - start;
        ToolResult timed = new ToolResult(result.status(), result.errorType(), result.message(),
                result.data(), result.retryable(), latency);
        toolCallLogService.logEnd(record, timed);
        executed.add(record.withOutcome(timed, latency));

        // SC-05：关键工具失败/超时中断依赖链
        if (tool.critical() && timed.status() == ToolStatus.FAILED) {
            return new ToolOutcome(toToolContent(call.id(), name, timed), true, FallbackReason.TOOL_FAILED);
        }
        if (tool.critical() && timed.status() == ToolStatus.TIMEOUT) {
            return new ToolOutcome(toToolContent(call.id(), name, timed), true, FallbackReason.TOOL_TIMEOUT);
        }
        return new ToolOutcome(toToolContent(call.id(), name, timed), false, null);
    }

    private void recordNotExecuted(ToolCall call, int round, int callSeq, String traceId, String openid,
                                   Long sessionId, List<ToolCallRecord> executed, ToolResult result) {
        ToolCallRecord record = toolCallLogService.logStart(traceId, openid, sessionId, call, round, callSeq);
        toolCallLogService.logEnd(record, result);
        executed.add(record.withOutcome(result, 0L));
    }

    private ToolResult executeWithTimeout(Tool tool, String traceId, String openid, Long sessionId,
                                          int round, JsonNode args) {
        ToolContext context = ToolContext.of(traceId, openid, sessionId, round);
        Callable<ToolResult> task = () -> tool.execute(context, args);
        Future<ToolResult> future = toolExecutor.submit(task);
        try {
            return future.get(orchestrationProperties.toolTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("工具执行超时（SC-03 单工具上限 {}ms）: tool={}",
                    orchestrationProperties.toolTimeoutMs(), tool.name());
            return ToolResult.timeout("工具执行超时");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            log.warn("工具执行异常: tool={} err={}", tool.name(), cause == null ? null : cause.getMessage());
            return ToolResult.failure("TOOL_FAILED", "工具执行失败", tool.idempotent());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.failure("TOOL_FAILED", "工具执行被中断", false);
        }
    }

    private static String toToolContent(String toolCallId, String name, ToolResult result) {
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("tool", name);
        payload.put("status", result.status().name());
        if (result.errorType() != null) {
            payload.put("error_type", result.errorType());
        }
        if (result.message() != null) {
            payload.put("message", result.message());
        }
        if (result.data() != null) {
            payload.put("data", result.data());
        }
        return JsonUtils.toJson(payload);
    }

    private static boolean hasUnsuccessful(List<ToolCallRecord> executed) {
        for (ToolCallRecord record : executed) {
            if (record.status() != ToolStatus.SUCCESS) {
                return true;
            }
        }
        return false;
    }

    private String effectiveModel() {
        return llmProperties.model();
    }

    /**
     * 将 LLM 异常细分映射为降级原因（D6 / SRS 9.5 降级矩阵）。
     *
     * <p>区分：超时（{@code LLM_TIMEOUT}）、连接失败/不可用（{@code LLM_UNAVAILABLE}）、
     * 输出格式非法（{@code LLM_INVALID_OUTPUT}）、配额超限（{@code BUDGET_EXCEEDED}）。
     *
     * @param e LLM 异常
     * @return 降级原因
     */
    private static FallbackReason mapLlmReason(LlmException e) {
        String type = e == null ? null : e.errorType();
        if (type == null) {
            return FallbackReason.LLM_UNAVAILABLE;
        }
        return switch (type) {
            case "LLM_TIMEOUT" -> FallbackReason.LLM_TIMEOUT;
            case "LLM_INVALID_OUTPUT" -> FallbackReason.LLM_INVALID_OUTPUT;
            case "LLM_QUOTA_EXCEEDED" -> FallbackReason.BUDGET_EXCEEDED;
            default -> FallbackReason.LLM_UNAVAILABLE;
        };
    }

    private OrchestrationResult fallback(OrchestrationRequest request, FallbackReason reason,
                                         List<ToolCallRecord> executed, int llmCalls, int rounds) {
        String text = fallbackService.render(reason, Map.of());
        SessionState state = (reason == FallbackReason.LLM_TIMEOUT || reason == FallbackReason.LLM_UNAVAILABLE
                || reason == FallbackReason.LLM_INVALID_OUTPUT)
                ? SessionState.DEGRADED : SessionState.IDLE;
        log.info("编排降级 reason={} llmCalls={} rounds={}", reason, llmCalls, rounds);
        return new OrchestrationResult(text, state, executed, reason.name(), llmCalls, rounds);
    }

    /**
     * 单工具执行产物（内部值对象）。
     *
     * @param messageContent  回注模型的内容
     * @param interrupt       是否中断依赖链（SC-05）
     * @param interruptReason 中断对应的降级原因
     */
    private record ToolOutcome(String messageContent, boolean interrupt, FallbackReason interruptReason) {
    }
}
