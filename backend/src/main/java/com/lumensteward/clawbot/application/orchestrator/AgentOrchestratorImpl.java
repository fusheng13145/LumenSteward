package com.lumensteward.clawbot.application.orchestrator;

import com.fasterxml.jackson.databind.JsonNode;
import com.lumensteward.clawbot.application.anomaly.AnomalyNotice;
import com.lumensteward.clawbot.application.config.ConfigKeys;
import com.lumensteward.clawbot.application.config.DynamicConfigService;
import com.lumensteward.clawbot.application.context.ContextTrimmer;
import com.lumensteward.clawbot.application.fallback.FallbackReason;
import com.lumensteward.clawbot.application.fallback.FallbackService;
import com.lumensteward.clawbot.application.orchestrator.model.OrchestrationRequest;
import com.lumensteward.clawbot.application.orchestrator.model.OrchestrationResult;
import com.lumensteward.clawbot.application.orchestrator.model.OrchestrationSpan;
import com.lumensteward.clawbot.application.console.ConsoleEvent;
import com.lumensteward.clawbot.application.console.ConsoleEventType;
import com.lumensteward.clawbot.application.memory.MemoryGrowthNotice;
import com.lumensteward.clawbot.application.memory.MemoryRecallService;
import com.lumensteward.clawbot.application.orchestrator.model.ToolCallRecord;
import com.lumensteward.clawbot.application.ratelimit.CostBudgetService;
import com.lumensteward.clawbot.application.task.SlotFillOutcome;
import com.lumensteward.clawbot.application.task.TaskSessionService;
import com.lumensteward.clawbot.application.safety.ConsistencyChecker;
import com.lumensteward.clawbot.application.safety.ConsistencyVerdict;
import com.lumensteward.clawbot.application.safety.ContentSafetyService;
import com.lumensteward.clawbot.application.safety.RuleBasedConsistencyChecker;
import com.lumensteward.clawbot.application.safety.SafetyVerdict;
import com.lumensteward.clawbot.common.enums.AnomalyLayer;
import com.lumensteward.clawbot.common.enums.SessionState;
import com.lumensteward.clawbot.common.enums.ToolStatus;
import com.lumensteward.clawbot.common.util.JsonUtils;
import com.lumensteward.clawbot.common.util.MaskUtils;
import com.lumensteward.clawbot.domain.context.ContextStore;
import com.lumensteward.clawbot.domain.model.TaskContext;
import com.lumensteward.clawbot.domain.tool.JsonSchema;
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
import com.lumensteward.clawbot.infrastructure.persistence.service.AuditLogService;
import com.lumensteward.clawbot.infrastructure.persistence.service.ToolCallLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    private final ApplicationEventPublisher eventPublisher;
    private final AuditLogService auditLogService;
    /** 任务型多步会话服务（FR-24 / T8；可为 null，此时退化为无跨轮任务记忆）。 */
    private final TaskSessionService taskSessionService;
    /** 个人状态库召回服务（W6 / §2.19；可为 null，此时本轮不注入长期记忆）。 */
    private final MemoryRecallService memoryRecallService;

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
     * @param eventPublisher        应用事件发布器（实时观测台事件）
     * @param auditLogService       审计日志服务（执行性幻觉拦截留痕，A-3 / T5；可为 null）
     * @param taskSessionService    任务型多步会话服务（FR-24 / T8；可为 null）
     * @param memoryRecallService   个人状态库召回服务（W6 / §2.19；可为 null，此时不注入长期记忆）
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
                                 MessageLengthGuard messageLengthGuard,
                                 ApplicationEventPublisher eventPublisher,
                                 AuditLogService auditLogService,
                                 TaskSessionService taskSessionService,
                                 MemoryRecallService memoryRecallService) {
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
        this.eventPublisher = eventPublisher;
        this.auditLogService = auditLogService;
        this.taskSessionService = taskSessionService;
        this.memoryRecallService = memoryRecallService;
    }

    /**
     * 兼容构造（无任务服务）：保留给既有单元测试与内部调用，行为等同「无跨轮任务记忆」。
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
     * @param dynamicConfig         动态配置源
     * @param costBudgetService     成本保护预算服务
     * @param messageLengthGuard    消息长度守卫
     * @param eventPublisher        应用事件发布器
     * @param auditLogService       审计日志服务
     */
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
                                 MessageLengthGuard messageLengthGuard,
                                 ApplicationEventPublisher eventPublisher,
                                 AuditLogService auditLogService) {
        this(llmClient, toolRegistry, contextStore, contextTrimmer, consistencyChecker,
                contentSafetyService, fallbackService, toolCallLogService,
                orchestrationProperties, llmProperties, dynamicConfig, costBudgetService,
                messageLengthGuard, eventPublisher, auditLogService, null, null);
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
                orchestrationProperties, llmProperties, null, costBudgetService, messageLengthGuard,
                null, null);
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
            publishConsole(ConsoleEventType.MESSAGE_DELTA, request.traceId(), request.openid(),
                    request.sessionId(), 0, null, null, text);
            publishConsole(ConsoleEventType.DONE, request.traceId(), request.openid(),
                    request.sessionId(), 0, null, null, null);
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
        // 链路时序记录器（A-5 / T6）：以链路起点为时间原点收集 span，链路结束发布追踪事件
        int totalBudgetMs = Math.max(1, dynamicInt(ConfigKeys.ORCHESTRATION_TOTAL_BUDGET_MS,
                orchestrationProperties.totalBudgetMs()));
        TraceRecorder trace = new TraceRecorder(traceId, openid, sessionId, totalBudgetMs);
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

        // ===== FR-24 任务型多步会话：优先续接既有任务（槽位填充），命中则跳过全量意图识别 =====
        String taskResumeHint = null;
        String topicSwitchNote = null;
        if (taskSessionService != null && openid != null && !openid.isBlank()) {
            SlotFillOutcome outcome = taskSessionService.fillSlot(openid, userMessage);
            switch (outcome.status()) {
                case SLOT_FILLED_COMPLETE -> {
                    TaskContext resumed = taskSessionService.resume(openid).orElse(outcome.context());
                    taskResumeHint = renderResumeHint(resumed);
                    log.info("任务槽位齐备，续接执行 openid={} taskType={}",
                            MaskUtils.openid(openid), resumed.taskType());
                }
                case SLOT_FILLED_INCOMPLETE, INVALID_REPROMPT -> {
                    String prompt = outcome.replyText();
                    publishConsole(ConsoleEventType.MESSAGE_DELTA, traceId, openid, sessionId, 0, null, null, prompt);
                    publishConsole(ConsoleEventType.DONE, traceId, openid, sessionId, 0, null, null, null);
                    return new OrchestrationResult(prompt, SessionState.TASKING, List.of(),
                            FallbackReason.MISSING_SLOT.name(), 0, 0);
                }
                case ABANDONED -> {
                    String text = outcome.replyText();
                    publishConsole(ConsoleEventType.MESSAGE_DELTA, traceId, openid, sessionId, 0, null, null, text);
                    publishConsole(ConsoleEventType.DONE, traceId, openid, sessionId, 0, null, null, null);
                    return new OrchestrationResult(text, SessionState.IDLE, List.of(),
                            FallbackReason.MISSING_SLOT.name(), 0, 0);
                }
                case TOPIC_SWITCH -> topicSwitchNote = outcome.replyText();
                case NO_TASK -> {
                    // 无活跃任务：走普通编排
                }
            }
        }

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
        // W6 / §2.19：注入该用户的跨会话长期记忆（fail-open，读不到即本轮无背景知识）
        String memoryBlock = memoryRecallService == null ? null : memoryRecallService.buildRecallBlock(openid);
        if (memoryBlock != null) {
            messages.add(ChatMessage.system(memoryBlock));
        }
        messages.addAll(trimmed);
        // FR-24：槽位齐备续接执行时，向模型注入已确认参数提示（不改变工具契约）
        if (taskResumeHint != null) {
            messages.add(ChatMessage.system(taskResumeHint));
        }
        messages.add(ChatMessage.user(userMessage));

        List<JsonNode> tools = toolRegistry.enabledSchemas(disabledTools());

        ChatResult response = null;
        while (round < maxRounds) {
            long llmStartNanos = System.nanoTime();
            try {
                response = llmClient.chat(ChatRequest.of(effectiveModel(), messages, tools, llmTimeout));
                recordUsage(response);
                llmCalls++;
            } catch (LlmException e) {
                FallbackReason reason = mapLlmReason(e);
                log.warn("LLM 调用失败，走降级: errType={} reason={}", e.errorType(), reason);
                publishAnomaly(e.errorType(), openid, "round=" + round);
                trace.recordLlm(round, llmStartNanos, OrchestrationSpan.SpanStatus.FAIL);
                return fallback(request, reason, executed, llmCalls, round, trace);
            }
            trace.recordLlm(round, llmStartNanos, OrchestrationSpan.SpanStatus.OK);

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
                ToolOutcome outcome = executeOne(call, round, callSeq[0] + 1, traceId, openid, sessionId,
                        executed, trace);
                callSeq[0]++;
                messages.add(ChatMessage.tool(call.id(), call.functionName(), outcome.messageContent()));
                if (outcome.interrupt()) {
                    return fallback(request, outcome.interruptReason(), executed, llmCalls, round, trace);
                }
            }
            round++;
        }

        // SC-04：达轮次上限 → 强制收敛（第 6 次调用）
        boolean forcedConvergence = round >= maxRounds;
        if (forcedConvergence) {
            messages.add(ChatMessage.system("请基于已有信息给出回复，并说明信息可能不完整。"));
            long convStartNanos = System.nanoTime();
            try {
                response = llmClient.chat(ChatRequest.of(effectiveModel(), messages, tools, llmTimeout));
                recordUsage(response);
                llmCalls++;
            } catch (LlmException e) {
                log.warn("强制收敛调用失败: errType={}", e.errorType());
                publishAnomaly(e.errorType(), openid, "forced_convergence");
                trace.recordLlm(round, convStartNanos, OrchestrationSpan.SpanStatus.FAIL);
                return fallback(request, FallbackReason.FORCED_CONVERGENCE, executed, llmCalls, round, trace);
            }
            trace.recordLlm(round, convStartNanos, OrchestrationSpan.SpanStatus.OK);
        }

        String reply = response == null ? null : response.content();
        if (reply == null || reply.isBlank()) {
            // 无终态文本：诚实兜底（不编造）
            return fallback(request, FallbackReason.EMPTY_RESULT, executed, llmCalls, round, trace);
        }

        // 输出治理 1：内容安全（Fail-Closed）
        SafetyVerdict safetyVerdict = contentSafetyService.review(reply);
        if (!safetyVerdict.passed()) {
            FallbackReason reason = safetyVerdict.serviceUnavailable()
                    ? FallbackReason.SAFETY_UNAVAILABLE : FallbackReason.CONTENT_BLOCKED;
            return fallback(request, reason, executed, llmCalls, round, trace);
        }

        // 输出治理 2：执行一致性校验（保守：任一声明无支撑即整条拦截）
        ConsistencyVerdict consistencyVerdict = consistencyChecker.check(reply, executed);
        if (!consistencyVerdict.passed()) {
            log.warn("一致性校验拦截 reply（BR-04），reason={}", consistencyVerdict.reason());
            // 执行性幻觉拦截留痕（A-3 / T5）：作为监控看板「幻觉拦截次数」的唯一事实来源。
            // 发送前强制拦截，故对外泄漏恒为 0；此处仅记录拦截事实（脱敏 openid，不落原文）。
            if (auditLogService != null) {
                auditLogService.record(null, "SAFETY", "EXECUTION_HALLUCINATION",
                        MaskUtils.openid(openid), null, consistencyVerdict.reason().name(),
                        "执行性幻觉拦截", null, 1);
            }
            return fallback(request, FallbackReason.EXECUTION_HALLUCINATION, executed, llmCalls, round, trace);
        }

        // 严格模式：存在失败工具调用时前置免责说明（9.4.5 异常流 3a）
        if (consistencyChecker instanceof RuleBasedConsistencyChecker ruleBased
                && ruleBased.strictMode() && hasUnsuccessful(executed)) {
            reply = "（提示：部分步骤未能完成）" + reply;
        }

        // FR-24：话题明显切换已中断先前任务，前置说明提醒用户（本回复服务新话题）
        if (topicSwitchNote != null && !topicSwitchNote.isBlank()) {
            reply = topicSwitchNote + reply;
        }

        // 写回上下文（best-effort）
        contextStore.appendAll(openid, List.of(ChatMessage.user(userMessage),
                ChatMessage.assistant(reply)));

        log.info("编排完成 openid={} rounds={} llmCalls={} tools={}",
                MaskUtils.openid(openid), round, llmCalls, executed.size());
        publishConsole(ConsoleEventType.MESSAGE_DELTA, traceId, openid, sessionId, round, null, null, reply);
        publishConsole(ConsoleEventType.DONE, traceId, openid, sessionId, round, null, null, null);
        // 链路结束：发布时序追踪事件（A-5 / T6，best-effort，落库失败不影响主链路）
        trace.publish(eventPublisher, log, round);
        // 链路结束：发布对话活动供个人状态库生长（W6，best-effort + 默认关闭，见 MemoryGrowthListener）
        publishMemoryGrowth(openid, sessionId, traceId, userMessage, reply);

        // ===== FR-24：本轮因缺必填参数暂停（追问）时建立任务，以承接用户后续补充 =====
        if (taskSessionService != null && sessionId != null) {
            MissingSlot missing = detectMissingSlot(executed);
            if (missing != null) {
                taskSessionService.createOrUpdate(openid, sessionId, missing.taskType(), missing.slots());
            }
        }
        return new OrchestrationResult(reply, SessionState.TASKING, executed, null, llmCalls, round);
    }

    /**
     * 从本轮已执行工具记录中识别「缺必填参数」的暂停点（FR-24 接入点）。
     *
     * <p>判定依据：工具返回 {@code INVALID_ARGS}（含 Schema 校验失败与工具内部缺参），据此取该工具
     * JSON-Schema 的 {@code required} 字段与已提供入参之差作为待填槽位。找不到则返回 null（不建立任务）。
     *
     * @param executed 本轮已执行工具记录
     * @return 缺参任务描述；无则 null
     */
    private MissingSlot detectMissingSlot(List<ToolCallRecord> executed) {
        if (executed == null || executed.isEmpty()) {
            return null;
        }
        for (ToolCallRecord record : executed) {
            if (!"INVALID_ARGS".equals(record.errorType()) || record.toolName() == null) {
                continue;
            }
            Optional<Tool> toolOpt = toolRegistry.find(record.toolName());
            if (toolOpt.isEmpty()) {
                continue;
            }
            List<String> required = requiredFields(toolOpt.get().parametersSchema());
            if (required.isEmpty()) {
                continue;
            }
            JsonNode params = record.params();
            List<String> missing = new ArrayList<>();
            for (String field : required) {
                if (params == null || !params.hasNonNull(field) || params.get(field).asText().isBlank()) {
                    missing.add(field);
                }
            }
            if (!missing.isEmpty()) {
                return new MissingSlot(record.toolName(), missing);
            }
        }
        return null;
    }

    /**
     * 读取工具参数的必填字段名（JSON-Schema {@code required}）。
     *
     * @param schema 工具参数 Schema
     * @return 必填字段名列表
     */
    private static List<String> requiredFields(JsonSchema schema) {
        JsonNode node = schema == null ? null : schema.node();
        JsonNode required = node == null ? null : node.get("required");
        List<String> fields = new ArrayList<>();
        if (required != null && required.isArray()) {
            for (JsonNode item : required) {
                if (item.isTextual() && !item.asText().isBlank()) {
                    fields.add(item.asText());
                }
            }
        }
        return fields;
    }

    /**
     * 生成槽位齐备续接执行的系统提示（向模型注入已确认参数，不改变工具契约）。
     *
     * @param task 已齐备的任务上下文
     * @return 系统提示文本
     */
    private static String renderResumeHint(TaskContext task) {
        if (task == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder("用户此前发起任务（")
                .append(task.taskType())
                .append("），已补齐全部必要参数：");
        int index = 0;
        for (Map.Entry<String, String> e : task.filledSlots().entrySet()) {
            if (index++ > 0) {
                sb.append("，");
            }
            sb.append(e.getKey()).append("=").append(e.getValue());
        }
        sb.append("。请直接基于这些参数完成该任务，不要再追问。");
        return sb.toString();
    }

    /**
     * 缺参任务描述（内部值对象）。
     *
     * @param taskType 任务类型（触发工具名）
     * @param slots    待填必填槽位
     */
    private record MissingSlot(String taskType, List<String> slots) {
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
     * @param trace     链路时序记录器（A-5 / T6）
     * @return 执行产物（含回注内容与是否中断）
     */
    private ToolOutcome executeOne(ToolCall call, int round, int callSeq, String traceId, String openid,
                                   Long sessionId, List<ToolCallRecord> executed, TraceRecorder trace) {
        String name = call.functionName();
        JsonNode args = JsonUtils.readTree(call.argumentsJson());

        // 工具未注册（模型编造工具名）：不执行，回注原因（9.4.3 第 25-29 行）
        java.util.Optional<Tool> toolOpt = toolRegistry.find(name);
        if (toolOpt.isEmpty()) {
            ToolResult result = ToolResult.notExecuted("TOOL_NOT_FOUND",
                    "工具未注册，可用工具: " + toolRegistry.names());
            recordNotExecuted(call, round, callSeq, traceId, openid, sessionId, executed, result, trace);
            return new ToolOutcome(toToolContent(call.id(), name, result), false, null);
        }
        Tool tool = toolOpt.get();

        // Schema 校验（9.4.3 第 31-35 行）
        ValidationResult validation = tool.parametersSchema().validate(args == null ? JsonUtils.mapper().createObjectNode() : args);
        if (!validation.valid()) {
            ToolResult result = ToolResult.notExecuted("INVALID_ARGS",
                    "参数非法: " + validation.describe());
            recordNotExecuted(call, round, callSeq, traceId, openid, sessionId, executed, result, trace);
            return new ToolOutcome(toToolContent(call.id(), name, result), false, null);
        }

        // 执行（同步日志：logStart → execute → logEnd，ADR-003）
        ToolCallRecord record = toolCallLogService.logStart(traceId, openid, sessionId, call, round, callSeq);
        publishConsole(ConsoleEventType.TOOL_START, traceId, openid, sessionId, round, name, callSeq, null);
        long toolStartNanos = System.nanoTime();
        long start = System.currentTimeMillis();
        ToolResult result = executeWithTimeout(tool, traceId, openid, sessionId, round, args);
        long latency = System.currentTimeMillis() - start;
        ToolResult timed = new ToolResult(result.status(), result.errorType(), result.message(),
                result.data(), result.retryable(), latency);
        toolCallLogService.logEnd(record, timed);
        trace.recordTool(round, name, toolStartNanos, toSpanStatus(timed.status()));
        publishConsole(ConsoleEventType.TOOL_END, traceId, openid, sessionId, round, name, callSeq,
                JsonUtils.toJson(java.util.Map.of("status", timed.status().name(), "latencyMs", timed.latencyMs())));
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
                                   Long sessionId, List<ToolCallRecord> executed, ToolResult result,
                                   TraceRecorder trace) {
        long toolStartNanos = System.nanoTime();
        ToolCallRecord record = toolCallLogService.logStart(traceId, openid, sessionId, call, round, callSeq);
        publishConsole(ConsoleEventType.TOOL_START, traceId, openid, sessionId, round, call.functionName(), callSeq, null);
        toolCallLogService.logEnd(record, result);
        trace.recordTool(round, call.functionName(), toolStartNanos, toSpanStatus(result.status()));
        publishConsole(ConsoleEventType.TOOL_END, traceId, openid, sessionId, round, call.functionName(), callSeq,
                JsonUtils.toJson(java.util.Map.of("status", result.status().name())));
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

    /**
     * 发布 L2 认知层异常事件（SRS 2.3.5 / 迭代 4 W1）。
     *
     * <p>此前 L2 异常只有一行 WARN 日志，A-3 四层分布因此在 L2 恒为 0；改为发布
     * {@link AnomalyNotice} 由 {@code AnomalyNoticeListener} 落 {@code log_anomaly_event}，
     * 编排器无需新增构造器依赖。与 {@link #publishConsole} 同纪律：观测副作用绝不影响主链路。
     *
     * @param errorCode LLM 异常码（{@code LlmException#errorType()}）
     * @param openid    用户标识（原始值，脱敏由落库侧统一完成）
     * @param detail    摘要（轮次等，不含 PII）
     */
    private void publishAnomaly(String errorCode, String openid, String detail) {
        if (eventPublisher == null) {
            return;
        }
        try {
            eventPublisher.publishEvent(new AnomalyNotice(
                    AnomalyLayer.L2, errorCode, "orchestrator", openid, detail));
        } catch (Exception e) {
            log.warn("发布 AnomalyNotice 失败 code={} openid={}", errorCode, MaskUtils.openid(openid));
        }
    }

    /**
     * 发布控制台观测事件（FR-08 T3）。
     *
     * <p>经 {@code ApplicationEventPublisher} 解耦到 SSE 缓冲；{@code eventPublisher} 为 null 时
     * （脱离 Spring 的测试构造路径）静默跳过。异常被吞掉并记告警，避免观测副作用影响主链路。
     *
     * @param type       事件类型
     * @param traceId    链路标识
     * @param openid     用户标识（内部脱敏）
     * @param sessionId  会话 id（可空）
     * @param round      Agent Loop 轮次（可空）
     * @param toolName   工具名（可空）
     * @param callSeq    链路内调用序号（可空）
     * @param payload    可选载荷 JSON（可空）
     */
    private void publishConsole(ConsoleEventType type, String traceId, String openid, Long sessionId,
                                Integer round, String toolName, Integer callSeq, String payload) {
        if (eventPublisher == null) {
            return;
        }
        try {
            eventPublisher.publishEvent(
                    new ConsoleEvent(type, traceId, openid, sessionId, round, toolName, callSeq, payload));
        } catch (Exception e) {
            log.warn("发布 ConsoleEvent 失败 type={} traceId={}", type, traceId);
        }
    }

    private String effectiveModel() {
        return dynamicString(ConfigKeys.LLM_MODEL, llmProperties.model());
    }

    /**
     * 发布本轮对话活动，供个人状态库异步生长（W6 / §2.19）。
     *
     * <p>仅在<b>回复已成功送达治理链之后</b>发布：降级回复是兜底文案，对其做抽取只会产出噪声。
     * 发布失败不影响本轮回复（与 {@code ConsoleEvent} 同一 best-effort 口径）。
     *
     * @param openid         用户标识（原始值，下游脱敏）
     * @param sessionId      会话 id（溯源）
     * @param traceId        链路标识（溯源）
     * @param userMessage    用户本轮原文
     * @param assistantReply 本轮终态回复
     */
    private void publishMemoryGrowth(String openid, Long sessionId, String traceId,
                                    String userMessage, String assistantReply) {
        if (eventPublisher == null) {
            return;
        }
        try {
            eventPublisher.publishEvent(new MemoryGrowthNotice(
                    openid, sessionId, traceId, userMessage, assistantReply));
        } catch (RuntimeException e) {
            log.warn("发布状态库生长事件失败（忽略） traceId={} err={}", traceId, e.getMessage());
        }
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
                                         List<ToolCallRecord> executed, int llmCalls, int rounds,
                                         TraceRecorder trace) {
        String text = fallbackService.render(reason, Map.of());
        SessionState state = (reason == FallbackReason.LLM_TIMEOUT || reason == FallbackReason.LLM_UNAVAILABLE
                || reason == FallbackReason.LLM_INVALID_OUTPUT)
                ? SessionState.DEGRADED : SessionState.IDLE;
        log.info("编排降级 reason={} llmCalls={} rounds={}", reason, llmCalls, rounds);
        publishConsole(ConsoleEventType.MESSAGE_DELTA, request.traceId(), request.openid(),
                request.sessionId(), rounds, null, null, text);
        publishConsole(ConsoleEventType.DONE, request.traceId(), request.openid(),
                request.sessionId(), rounds, null, null, null);
        // 降级链路同样发布时序追踪（A-5 / T6，best-effort）
        if (trace != null) {
            trace.publish(eventPublisher, log, rounds);
        }
        return new OrchestrationResult(text, state, executed, reason.name(), llmCalls, rounds);
    }

    /**
     * 工具状态 → 时序 span 状态映射（A-5 / T6）。
     *
     * @param status 工具执行状态（可空）
     * @return span 状态；未执行（工具未注册 / 参数非法）与失败统一记为 {@code FAIL}
     */
    private static OrchestrationSpan.SpanStatus toSpanStatus(ToolStatus status) {
        if (status == null) {
            return OrchestrationSpan.SpanStatus.FAIL;
        }
        return switch (status) {
            case SUCCESS -> OrchestrationSpan.SpanStatus.OK;
            case DEGRADED -> OrchestrationSpan.SpanStatus.DEGRADED;
            case TIMEOUT -> OrchestrationSpan.SpanStatus.TIMEOUT;
            case FAILED, NOT_EXECUTED -> OrchestrationSpan.SpanStatus.FAIL;
        };
    }

    /**
     * 链路时序记录器（A-5 / T6）。
     *
     * <p>以链路起点 {@code nanoTime} 为时间原点，收集各 span 的<b>相对偏移</b>与<b>耗时</b>，
     * 并在链路结束时组装 {@link OrchestrationTracedEvent} 经 {@link ApplicationEventPublisher}
     * 发布。发布失败仅记 WARN，<b>不影响主链路</b>（与 {@code publishConsole} 同范式）。
     *
     * <p>{@code seq} 由 span 的加入顺序自增（从 1），供瀑布图纵轴排序。
     */
    private static final class TraceRecorder {

        private final String traceId;
        private final String openid;
        private final Long sessionId;
        private final long startNanos;
        private final int totalBudgetMs;
        private final List<OrchestrationSpan> spans = new ArrayList<>();

        /**
         * @param traceId       链路标识
         * @param openid        用户标识（由事件构造脱敏）
         * @param sessionId     会话 id
         * @param totalBudgetMs 链路总预算（ms）
         */
        TraceRecorder(String traceId, String openid, Long sessionId, int totalBudgetMs) {
            this.traceId = traceId;
            this.openid = openid;
            this.sessionId = sessionId;
            this.totalBudgetMs = totalBudgetMs;
            this.startNanos = System.nanoTime();
        }

        /**
         * 记录一轮 LLM 调用 span。
         *
         * @param round          轮次（0 基）
         * @param spanStartNanos 该次调用开始的 {@code nanoTime}
         * @param status         span 状态
         */
        void recordLlm(int round, long spanStartNanos, OrchestrationSpan.SpanStatus status) {
            record(OrchestrationSpan.SpanKind.LLM_ROUND, round, "LLM#" + round, spanStartNanos, status);
        }

        /**
         * 记录一次工具调用 span。
         *
         * @param round          所属轮次
         * @param toolName       工具名
         * @param spanStartNanos 该次调用开始的 {@code nanoTime}
         * @param status         span 状态
         */
        void recordTool(int round, String toolName, long spanStartNanos, OrchestrationSpan.SpanStatus status) {
            record(OrchestrationSpan.SpanKind.TOOL, round, toolName, spanStartNanos, status);
        }

        private void record(OrchestrationSpan.SpanKind kind, int round, String name,
                            long spanStartNanos, OrchestrationSpan.SpanStatus status) {
            long endNanos = System.nanoTime();
            long offsetMs = Math.max(0L, (spanStartNanos - startNanos) / 1_000_000L);
            long durationMs = Math.max(0L, (endNanos - spanStartNanos) / 1_000_000L);
            spans.add(new OrchestrationSpan(kind, spans.size() + 1, round, name,
                    offsetMs, durationMs, status));
        }

        /**
         * 组装并发布链路时序事件（best-effort）。
         *
         * @param publisher 应用事件发布器（为 null 时跳过）
         * @param logger    调用方 logger（发布失败记 WARN）
         * @param rounds    Agent Loop 轮次
         */
        void publish(ApplicationEventPublisher publisher, Logger logger, int rounds) {
            if (publisher == null) {
                return;
            }
            try {
                long totalMs = Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
                publisher.publishEvent(new OrchestrationTracedEvent(traceId, openid, sessionId,
                        totalMs, totalBudgetMs, rounds, totalMs > totalBudgetMs, spans));
            } catch (Exception e) {
                logger.warn("发布 OrchestrationTracedEvent 失败 traceId={}", traceId);
            }
        }
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
