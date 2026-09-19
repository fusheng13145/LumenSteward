package com.lumensteward.clawbot.application.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.lumensteward.clawbot.application.config.ConfigKeys;
import com.lumensteward.clawbot.application.config.DynamicConfigService;
import com.lumensteward.clawbot.application.context.ContextTrimmer;
import com.lumensteward.clawbot.application.fallback.FallbackReason;
import com.lumensteward.clawbot.application.fallback.FallbackService;
import com.lumensteward.clawbot.application.orchestrator.model.OrchestrationRequest;
import com.lumensteward.clawbot.application.orchestrator.model.OrchestrationResult;
import com.lumensteward.clawbot.application.orchestrator.model.ToolCallRecord;
import com.lumensteward.clawbot.application.ratelimit.CostBudgetService;
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
import com.lumensteward.clawbot.application.validation.MessageLengthGuard;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private final DynamicConfigService dynamicConfig;
    private final CostBudgetService costBudgetService;
    private final MessageLengthGuard messageLengthGuard;

    /** 工具执行超时隔离线程池（daemon，避免阻塞 JVM 退出）。 */
    private final ExecutorService toolExecutor = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "clawbot-tool-exec");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Spring 装配用构造器（G-14）。
     *
     * <p>迭代 2 T10/B-2：追加 {@link DynamicConfigService}，使模型名、轮次上限、工具开关等
     * 在<b>运行时</b>从 {@code sys_config} 取值，管理后台改值免重启生效（FR-18 AC① / B-2 AC①）。
     *
     * @param llmClient             LLM 客户端
     * @param toolRegistry          工具注册中心
     * @param contextStore          上下文存储
     * @param contextTrimmer        上下文裁剪器
     * @param consistencyChecker    执行一致性校验
     * @param contentSafetyService  内容安全
     * @param fallbackService       兜底文案
     * @param toolCallLogService    工具调用日志（同步）
     * @param orchestrationProperties 编排配置（静态兜底值）
     * @param llmProperties         LLM 配置（静态兜底值）
     * @param dynamicConfig         动态配置源（可为 null，此时行为等同静态配置）
     * @param costBudgetService     成本保护预算服务（可为 null）
     * @param messageLengthGuard    消息长度守卫（可为 null）
     */
    @Autowired
    public AgentOrchestratorImpl(LlmClient llmClient, ToolRegistry toolRegistry,
                                 ContextStore contextStore, ContextTrimmer contextTrimmer,
                                 ConsistencyChecker consistencyChecker,
                                 ContentSafetyService contentSafetyService,
                                 FallbackService fallbackService,
                                 ToolCallLogService toolCallLogService,
                                 OrchestrationProperties orchestrationProperties,
                                 LlmProperties llmProperties,
                                 DynamicConfigService dynamicConfig,
                                 CostBudgetService costBudgetService,
                                 MessageLengthGuard messageLengthGuard) {
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
        this.dynamicConfig = dynamicConfig;
        this.costBudgetService = costBudgetService;
        this.messageLengthGuard = messageLengthGuard;
    }

    /**
     * 兼容构造（无动态配置源）：保留给脱离 Spring 上下文的单元测试，行为等同静态配置。
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
     * @param llmProperties         LLM 配置
     * @param costBudgetService     成本保护预算服务
     * @param messageLengthGuard    消息长度守卫
     */
    public AgentOrchestratorImpl(LlmClient llmClient, ToolRegistry toolRegistry,
                                 ContextStore contextStore, ContextTrimmer contextTrimmer,
                                 ConsistencyChecker consistencyChecker,
                                 ContentSafetyService contentSafetyService,
                                 FallbackService fallbackService,
                                 ToolCallLogService toolCallLogService,
                                 OrchestrationProperties orchestrationProperties,
                                 LlmProperties llmProperties,
                                 CostBudgetService costBudgetService,
                                 MessageLengthGuard messageLengthGuard) {
        this(llmClient, toolRegistry, contextStore, contextTrimmer, consistencyChecker,
                contentSafetyService, fallbackService, toolCallLogService,
                orchestrationProperties, llmProperties, null, costBudgetService, messageLengthGuard);
    }

    @Override
    public OrchestrationResult run(OrchestrationRequest request) {
        if (request == null || request.userMessage() == null || request.userMessage().isBlank()) {
            String text = fallbackService.render(FallbackReason.INVALID_ARGS, Map.of());
            return new OrchestrationResult(text, SessionState.IDLE, List.of(),
                    FallbackReason.INVALID_ARGS.name(), 0, 0);
        }
        // 成本预算耗尽降级（FR-20 ③）：不再调用 LLM，直接返回基础回复
        if (costBudgetService != null && costBudgetService.isDegraded()) {
            String text = "今天的使用额度已用完啦，明天零点会重新开放，先和你道个晚安～";
            log.info("成本预算耗尽，进入基础回复降级 openid={}", MaskUtils.openid(request.openid()));
            return new OrchestrationResult(text, SessionState.DEGRADED, List.of(),
                    "COST_BUDGET", 0, 0);
        }
        // 单条消息长度守卫（FR-20 ④）
        String userMessage = request.userMessage();
        if (messageLengthGuard != null) {
            var guard = messageLengthGuard.guard(userMessage);
            if (guard.truncated()) {
                log.warn("消息超长已截断 openid={} originalLen={}", MaskUtils.openid(request.openid()),
                        guard.originalLength());
            }
            userMessage = guard.text();
        }
        String traceId = resolveTraceId(request);
        String openid = request.openid();
        Long sessionId = request.sessionId();
        int maxRounds = Math.max(1, dynamicInt(ConfigKeys.ORCHESTRATION_MAX_ROUNDS,
                orchestrationProperties.maxRounds()));
        int maxParallel = Math.max(1, dynamicInt(ConfigKeys.ORCHESTRATION_MAX_PARALLEL_TOOLS,
                orchestrationProperties.maxParallelTools()));
        Duration llmTimeout = Duration.ofSeconds(Math.max(1,
                dynamicInt(ConfigKeys.LLM_TIMEOUT_SECONDS, llmProperties.timeoutSeconds())));

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
        List<ChatMessage> trimmed = contextTrimmer.trim(history,
                dynamicInt(ConfigKeys.LLM_INPUT_BUDGET_TOKENS, llmProperties.inputBudgetTokens()),
                dynamicInt(ConfigKeys.LLM_RESERVED_OUTPUT_TOKENS, llmProperties.reservedOutputTokens()));

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(SYSTEM_PROMPT));
        messages.addAll(trimmed);
        messages.add(ChatMessage.user(userMessage));

        List<JsonNode> tools = toolRegistry.enabledSchemas(disabledTools());

        ChatResult response = null;
        while (round < maxRounds) {
            try {
                response = llmClient.chat(ChatRequest.of(effectiveModel(), messages, tools, llmTimeout));
                recordUsage(response);
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
                recordUsage(response);
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
        contextStore.appendAll(openid, List.of(ChatMessage.user(userMessage),
                ChatMessage.assistant(reply)));

        log.info("编排完成 openid={} rounds={} llmCalls={} tools={}",
                MaskUtils.openid(openid), round, llmCalls, executed.size());
        return new OrchestrationResult(reply, SessionState.TASKING, executed, null, llmCalls, round);
    }

    /**
     * 解析链路标识（D7 补强）。
     *
     * <p>优先取请求携带的 traceId，其次取当前线程 MDC（{@link TraceContext}）。二者均缺失时
     * <b>生成一次性 traceId</b>——{@code log_tool_call.trace_id} 为 {@code NOT NULL} 列，若无兜底，
     * 缺 traceId 时 {@code logStart} 会因 NOT NULL 约束失败并（修复前）被静默吞掉，
     * 再次造成 {@code log_tool_call} 运行期恒空。此兜底确保「工具日志同步落库」（ADR-003）在任何入口下都成立。
     *
     * @param request 编排请求
     * @return 非空 traceId
     */
    private static String resolveTraceId(OrchestrationRequest request) {
        String traceId = request.traceId();
        if (traceId == null || traceId.isBlank()) {
            traceId = TraceContext.getTraceId();
        }
        return (traceId == null || traceId.isBlank()) ? TraceContext.newTraceId() : traceId;
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
            return future.get(toolTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("工具执行超时（SC-03 单工具上限 {}ms）: tool={}",
                    toolTimeoutMs(), tool.name());
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
        return dynamicString(ConfigKeys.LLM_MODEL, llmProperties.model());
    }

    /**
     * 记录本次 LLM 调用的 token 消耗（FR-20 ③ 成本保护）。
     *
     * @param response 对话结果（可空）
     */
    private void recordUsage(ChatResult response) {
        if (costBudgetService == null || response == null || response.usage() == null) {
            return;
        }
        costBudgetService.recordLlmCall(response.usage().totalTokens());
    }

    /**
     * 运行时读取整数配置（FR-18 AC①）：动态源缺失或该项未配置时回退启动期静态值。
     *
     * @param key      配置键
     * @param fallback 兜底值
     * @return 整数值
     */
    private int dynamicInt(String key, int fallback) {
        return dynamicConfig == null ? fallback : dynamicConfig.getInt(key, fallback);
    }

    /**
     * 运行时读取字符串配置（B-2 AC①：切换 {@code llm.model} 免重启生效）。
     *
     * @param key      配置键
     * @param fallback 兜底值
     * @return 字符串值
     */
    private String dynamicString(String key, String fallback) {
        return dynamicConfig == null ? fallback : dynamicConfig.getString(key, fallback);
    }

    /**
     * 运行时工具开关（FR-18）：配置值为 JSON 数组或逗号分隔，空则回退静态禁用集合。
     *
     * @return 被禁用工具名集合
     */
    private Set<String> disabledTools() {
        Set<String> staticDisabled = orchestrationProperties.disabledTools() == null
                ? Set.of() : orchestrationProperties.disabledTools();
        if (dynamicConfig == null) {
            return staticDisabled;
        }
        List<String> configured = dynamicConfig.getList(ConfigKeys.ORCHESTRATION_DISABLED_TOOLS, null);
        if (configured == null) {
            return staticDisabled;
        }
        return new HashSet<>(configured);
    }

    /**
     * 单工具执行超时（ms，SC-03），运行时可配置。
     *
     * @return 超时毫秒数
     */
    private long toolTimeoutMs() {
        return dynamicInt(ConfigKeys.ORCHESTRATION_TOOL_TIMEOUT_MS,
                orchestrationProperties.toolTimeoutMs());
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
