package com.lumensteward.clawbot.application.task;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lumensteward.clawbot.common.enums.SessionState;
import com.lumensteward.clawbot.common.util.JsonUtils;
import com.lumensteward.clawbot.domain.intent.IntentClassifier;
import com.lumensteward.clawbot.domain.intent.IntentResult;
import com.lumensteward.clawbot.domain.intent.IntentType;
import com.lumensteward.clawbot.domain.model.TaskContext;
import com.lumensteward.clawbot.domain.task.TaskStore;
import com.lumensteward.clawbot.domain.tool.ToolRegistry;
import com.lumensteward.clawbot.infrastructure.persistence.entity.WxSessionEntity;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.WxSessionMapper;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * {@link TaskSessionService} 实现（SRS FR-24 / BR-33 / BR-34）。
 *
 * <p><b>存储分层：</b>任务栈活跃态存 Redis {@code task:{openid}}（TTL 10 分钟，2 个上限，栈式）；
 * 任务进度镜像 {@code wx_session.task_context} 并同步 {@code state}，供管理后台观测。
 *
 * <p><b>并发：</b>以 Redisson {@code lock:task:{openid}} 串行化同一用户的任务读写；锁不可用时降级为
 * 「无锁执行」（Redis 侧 TTL 与 DB 状态兜底，不阻断主链路）。
 *
 * <p><b>时间：</b>经 {@link Clock} 取当前时刻，便于测试注入以验证超时清理。
 */
@Service
public class TaskSessionServiceImpl implements TaskSessionService {

    private static final Logger log = LoggerFactory.getLogger(TaskSessionServiceImpl.class);

    /** 任务活跃态 TTL（FR-24：默认 10 分钟无交互）。 */
    private static final Duration TASK_TTL = Duration.ofMinutes(10);

    /** 多任务并存上限（栈式，超限丢弃最旧）。 */
    private static final int MAX_CONCURRENT_TASKS = 2;

    /** 连续无效填充上限（达此值即放弃，BR-33）。 */
    private static final int MAX_INVALID_ATTEMPTS = 3;

    /** 分布式锁等待时长（秒）。 */
    private static final long LOCK_WAIT_SECONDS = 3L;

    /** 话题切换说明（前置到正常回复）。 */
    private static final String TOPIC_SWITCH_NOTE = "（已暂停之前的操作）";

    /** 放弃说明（BR-33：明确说明，且不出现下一次追问）。 */
    private static final String ABANDON_TEXT =
            "抱歉，我没能获取到需要的信息，暂时没法完成这个操作。你可以稍后再试，或换个说法告诉我～";

    /** 槽位中文标签（用于确定性追问文案）。 */
    private static final Map<String, String> SLOT_LABELS = Map.of(
            "tracking_no", "快递单号",
            "company_code", "快递公司",
            "pet_name", "宠物昵称",
            "pet_type", "宠物类型",
            "action", "要执行的操作");

    private final TaskStore store;
    private final WxSessionMapper sessionMapper;
    private final SlotFiller slotFiller;
    private final IntentClassifier intentClassifier;
    private final RedissonClient redissonClient;

    /** 「工具名 → 意图」由工具自述后经注册表反查（FR-23：新增工具零改本类）。 */
    private final ToolRegistry toolRegistry;
    private final Clock clock;

    /**
     * Spring 装配用构造器（G-14 / D8：多构造器 Bean 于生产构造器显式 {@code @Autowired}）。
     *
     * @param store            任务活跃态存储
     * @param sessionMapper    会话 Mapper（持久化 task_context / state）
     * @param slotFiller       槽位填充器
     * @param intentClassifier 意图分类器（话题切换判定）
     * @param redissonClient   Redisson 客户端（分布式锁；可为 null）
     * @param toolRegistry     工具注册表（意图归因反查）
     */
    @Autowired
    public TaskSessionServiceImpl(TaskStore store, WxSessionMapper sessionMapper, SlotFiller slotFiller,
                                  IntentClassifier intentClassifier, RedissonClient redissonClient,
                                  ToolRegistry toolRegistry) {
        this(store, sessionMapper, slotFiller, intentClassifier, redissonClient, toolRegistry,
                Clock.systemUTC());
    }

    /**
     * 测试用构造器：可注入 {@link Clock} 以验证超时清理。
     *
     * @param store            任务活跃态存储
     * @param sessionMapper    会话 Mapper
     * @param slotFiller       槽位填充器
     * @param intentClassifier 意图分类器
     * @param redissonClient   Redisson 客户端（可为 null）
     * @param toolRegistry     工具注册表（话题切换判定所需的意图反查）
     * @param clock            时钟
     */
    public TaskSessionServiceImpl(TaskStore store, WxSessionMapper sessionMapper, SlotFiller slotFiller,
                                  IntentClassifier intentClassifier, RedissonClient redissonClient,
                                  ToolRegistry toolRegistry, Clock clock) {
        this.store = store;
        this.sessionMapper = sessionMapper;
        this.slotFiller = slotFiller;
        this.intentClassifier = intentClassifier;
        this.redissonClient = redissonClient;
        this.toolRegistry = toolRegistry;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Override
    public Optional<TaskContext> createOrUpdate(String openid, Long sessionId, String taskType,
                                                List<String> requiredSlots) {
        if (blank(openid) || taskType == null || requiredSlots == null || requiredSlots.isEmpty()) {
            return Optional.empty();
        }
        return withLock(openid, () -> {
            Instant now = clock.instant();
            List<TaskContext> stack = loadStack(openid);
            purgeExpired(stack, openid, now);

            TaskContext top = stack.isEmpty() ? null : stack.get(stack.size() - 1);
            TaskContext task;
            if (top != null && top.taskType().equals(taskType)) {
                // 栈顶同类型：合并槽位，保留已填内容，刷新过期时刻（幂等更新，不重复压栈）
                task = new TaskContext(taskType, union(top.requiredSlots(), requiredSlots), top.filledSlots(),
                        now.plus(TASK_TTL), Math.max(1, top.promptCount()), top.invalidAttempts(), top.createdAt());
                stack.set(stack.size() - 1, task);
            } else {
                task = TaskContext.newTask(taskType, requiredSlots, now.plus(TASK_TTL), now);
                stack.add(task);
                if (stack.size() > MAX_CONCURRENT_TASKS) {
                    stack = new ArrayList<>(stack.subList(stack.size() - MAX_CONCURRENT_TASKS, stack.size()));
                }
            }
            // Redis 写失败（不可用）→ 视为任务取消，不维持跨轮状态
            if (!persist(openid, stack)) {
                log.warn("任务活跃态写入失败，视为取消 openid={} taskType={}",
                        com.lumensteward.clawbot.common.util.MaskUtils.openid(openid), taskType);
                return Optional.empty();
            }
            syncState(sessionId, SessionState.TASKING, task);
            log.info("建立/更新任务 openid={} taskType={} requiredSlots={}",
                    com.lumensteward.clawbot.common.util.MaskUtils.openid(openid), taskType, requiredSlots);
            return Optional.of(task);
        });
    }

    @Override
    public SlotFillOutcome fillSlot(String openid, String userMessage) {
        if (blank(openid)) {
            return SlotFillOutcome.noTask();
        }
        return withLock(openid, () -> doFillSlot(openid, userMessage));
    }

    private SlotFillOutcome doFillSlot(String openid, String userMessage) {
        Instant now = clock.instant();
        List<TaskContext> stack = loadStack(openid);
        purgeExpired(stack, openid, now);
        if (stack.isEmpty()) {
            return SlotFillOutcome.noTask();
        }
        int idx = stack.size() - 1;
        TaskContext top = stack.get(idx);
        Optional<Map<String, String>> fill = slotFiller.fill(top.pendingSlots(), userMessage);
        if (fill.isPresent() && !fill.get().isEmpty()) {
            TaskContext updated = top.withFilled(fill.get(), now.plus(TASK_TTL));
            stack.set(idx, updated);
            persist(openid, stack);
            syncState(resolveSessionId(openid), SessionState.TASKING, updated);
            if (updated.complete()) {
                log.info("任务槽位齐备，待续接 openid={} taskType={}",
                        com.lumensteward.clawbot.common.util.MaskUtils.openid(openid), updated.taskType());
                return SlotFillOutcome.complete(updated);
            }
            return SlotFillOutcome.incomplete(updated, renderPrompt(updated));
        }
        // 未填充：先判定是否话题明显切换（BR-34 / FR-24：切换即中断并清理）
        if (isTopicSwitch(top.taskType(), classify(userMessage))) {
            stack.remove(idx);
            persist(openid, stack);
            syncStateResolved(openid, stack);
            log.info("话题切换，中断并清理任务 openid={}",
                    com.lumensteward.clawbot.common.util.MaskUtils.openid(openid));
            return SlotFillOutcome.topicSwitch(top, TOPIC_SWITCH_NOTE);
        }
        // 无效输入：累计；达阈值即放弃（BR-33，不出现下一次追问）
        TaskContext attempted = top.withInvalidAttempt(now.plus(TASK_TTL));
        if (attempted.invalidAttempts() >= MAX_INVALID_ATTEMPTS) {
            stack.remove(idx);
            persist(openid, stack);
            syncStateResolved(openid, stack);
            log.info("连续 {} 次无效输入，放弃任务 openid={}", MAX_INVALID_ATTEMPTS,
                    com.lumensteward.clawbot.common.util.MaskUtils.openid(openid));
            return SlotFillOutcome.abandoned(attempted, ABANDON_TEXT);
        }
        stack.set(idx, attempted);
        persist(openid, stack);
        syncState(resolveSessionId(openid), SessionState.TASKING, attempted);
        return SlotFillOutcome.invalid(attempted, renderPrompt(attempted));
    }

    @Override
    public Optional<TaskContext> resume(String openid) {
        if (blank(openid)) {
            return Optional.empty();
        }
        return withLock(openid, () -> {
            Instant now = clock.instant();
            List<TaskContext> stack = loadStack(openid);
            purgeExpired(stack, openid, now);
            if (stack.isEmpty()) {
                return Optional.empty();
            }
            TaskContext top = stack.get(stack.size() - 1);
            if (!top.complete()) {
                return Optional.empty();
            }
            stack.remove(stack.size() - 1);
            persist(openid, stack);
            syncStateResolved(openid, stack);
            return Optional.of(top);
        });
    }

    @Override
    public Optional<TaskContext> current(String openid) {
        if (blank(openid)) {
            return Optional.empty();
        }
        return withLock(openid, () -> Optional.ofNullable(peekTop(openid)));
    }

    @Override
    public boolean detectTopicSwitch(String openid, String userMessage) {
        if (blank(openid)) {
            return false;
        }
        TaskContext top = peekTop(openid);
        return top != null && isTopicSwitch(top.taskType(), classify(userMessage));
    }

    @Override
    public Optional<TaskContext> abandon(String openid) {
        if (blank(openid)) {
            return Optional.empty();
        }
        return withLock(openid, () -> {
            List<TaskContext> stack = loadStack(openid);
            if (stack.isEmpty()) {
                return Optional.empty();
            }
            TaskContext top = stack.remove(stack.size() - 1);
            persist(openid, stack);
            syncStateResolved(openid, stack);
            return Optional.of(top);
        });
    }

    @Override
    public Optional<SessionTaskView> findBySession(Long sessionId) {
        WxSessionEntity entity = safeSelectById(sessionId);
        if (entity == null) {
            return Optional.empty();
        }
        return Optional.of(new SessionTaskView(entity.getState(), parseTask(entity.getTaskContext())));
    }

    @Override
    public Optional<TaskContext> abandonBySession(Long sessionId) {
        WxSessionEntity entity = safeSelectById(sessionId);
        if (entity == null) {
            return Optional.empty();
        }
        String openid = entity.getOpenid();
        return withLock(openid, () -> {
            List<TaskContext> stack = loadStack(openid);
            TaskContext top = stack.isEmpty() ? null : stack.remove(stack.size() - 1);
            persist(openid, stack);
            TaskContext newTop = stack.isEmpty() ? null : stack.get(stack.size() - 1);
            syncState(sessionId, stack.isEmpty() ? SessionState.IDLE : SessionState.TASKING, newTop);
            return Optional.ofNullable(top);
        });
    }

    // ===== 内部方法 =====

    /**
     * 串行化同一用户的任务读写；Redisson 不可用或抢锁超时时降级为无锁执行。
     *
     * @param openid 用户标识
     * @param action 临界区逻辑
     * @param <T>    返回类型
     * @return 临界区返回值
     */
    private <T> T withLock(String openid, Supplier<T> action) {
        if (redissonClient == null) {
            return action.get();
        }
        RLock lock = null;
        try {
            lock = redissonClient.getLock("lock:task:" + openid);
            if (!lock.tryLock(LOCK_WAIT_SECONDS, TimeUnit.SECONDS)) {
                return action.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return action.get();
        } catch (RuntimeException e) {
            log.warn("获取任务锁失败，降级为无锁执行: err={}", e.getMessage());
            return action.get();
        }
        try {
            return action.get();
        } finally {
            if (lock != null && lock.isHeldByCurrentThread()) {
                try {
                    lock.unlock();
                } catch (RuntimeException e) {
                    log.warn("释放任务锁失败: err={}", e.getMessage());
                }
            }
        }
    }

    private List<TaskContext> loadStack(String openid) {
        return store.load(openid).map(ArrayList::new).orElseGet(ArrayList::new);
    }

    private boolean persist(String openid, List<TaskContext> stack) {
        if (stack == null || stack.isEmpty()) {
            store.delete(openid);
            return true;
        }
        return store.save(openid, stack, TASK_TTL);
    }

    /**
     * 清理过期任务；清理干净后删除 Redis 键并同步会话为空闲（FR-24 超时清理）。
     *
     * @param stack  任务栈（原地修改）
     * @param openid 用户标识
     * @param now    当前时刻
     * @return 是否有任务被清理
     */
    private boolean purgeExpired(List<TaskContext> stack, String openid, Instant now) {
        boolean changed = false;
        Iterator<TaskContext> it = stack.iterator();
        while (it.hasNext()) {
            if (it.next().expired(now)) {
                it.remove();
                changed = true;
            }
        }
        if (changed) {
            persist(openid, stack);
            syncStateResolved(openid, stack);
        }
        return changed;
    }

    private TaskContext peekTop(String openid) {
        Instant now = clock.instant();
        List<TaskContext> stack = loadStack(openid);
        purgeExpired(stack, openid, now);
        return stack.isEmpty() ? null : stack.get(stack.size() - 1);
    }

    private void syncStateResolved(String openid, List<TaskContext> stack) {
        boolean empty = stack == null || stack.isEmpty();
        TaskContext top = empty ? null : stack.get(stack.size() - 1);
        syncState(resolveSessionId(openid), empty ? SessionState.IDLE : SessionState.TASKING, top);
    }

    /**
     * 同步会话状态与任务上下文（best-effort，不阻断主链路）。
     *
     * <p>{@code task} 为空时<b>显式置空</b> {@code task_context} 列（清理路径）。
     *
     * @param sessionId 会话 id（可空则跳过）
     * @param state     目标状态
     * @param task      任务上下文（可空）
     */
    private void syncState(Long sessionId, SessionState state, TaskContext task) {
        if (sessionId == null || sessionMapper == null) {
            return;
        }
        try {
            String json = task == null ? null : JsonUtils.toJson(task);
            sessionMapper.update(null, Wrappers.<WxSessionEntity>lambdaUpdate()
                    .eq(WxSessionEntity::getId, sessionId)
                    .set(WxSessionEntity::getState, state.getCode())
                    .set(WxSessionEntity::getTaskContext, json)
                    .set(WxSessionEntity::getLastActiveAt, LocalDateTime.now()));
        } catch (RuntimeException e) {
            log.warn("同步会话任务状态失败（不阻断主链路）sessionId={} err={}", sessionId, e.getMessage());
        }
    }

    private Long resolveSessionId(String openid) {
        if (blank(openid) || sessionMapper == null) {
            return null;
        }
        try {
            WxSessionEntity entity = sessionMapper.selectOne(Wrappers.<WxSessionEntity>lambdaQuery()
                    .eq(WxSessionEntity::getOpenid, openid).last("limit 1"));
            return entity == null ? null : entity.getId();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private WxSessionEntity safeSelectById(Long sessionId) {
        if (sessionId == null || sessionMapper == null) {
            return null;
        }
        try {
            return sessionMapper.selectById(sessionId);
        } catch (RuntimeException e) {
            log.warn("读取会话失败（不阻断主链路）sessionId={} err={}", sessionId, e.getMessage());
            return null;
        }
    }

    private static TaskContext parseTask(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        return JsonUtils.fromJson(json, TaskContext.class);
    }

    private boolean isTopicSwitch(String taskType, IntentResult intent) {
        if (intent == null || intent.intent() == null
                || intent.intent() == IntentType.UNKNOWN || intent.lowConfidence()) {
            return false;
        }
        IntentType taskIntent = toolRegistry.taskIntentOf(taskType);
        if (taskIntent == null) {
            return false;
        }
        return intent.intent() != taskIntent;
    }

    private IntentResult classify(String userMessage) {
        if (intentClassifier == null || userMessage == null || userMessage.isBlank()) {
            return IntentResult.unknown();
        }
        try {
            IntentResult result = intentClassifier.classify(List.of(), userMessage);
            return result == null ? IntentResult.unknown() : result;
        } catch (RuntimeException e) {
            log.warn("话题切换判定：意图分类失败，按非切换处理 err={}", e.getMessage());
            return IntentResult.unknown();
        }
    }

    private static String renderPrompt(TaskContext task) {
        List<String> pending = task.pendingSlots();
        if (pending.isEmpty()) {
            return "请补充所需信息，我来继续帮你处理～";
        }
        String label = SLOT_LABELS.getOrDefault(pending.get(0), pending.get(0));
        return "好的，请把" + label + "告诉我，我来帮你继续处理～";
    }

    private static List<String> union(List<String> first, List<String> second) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (first != null) {
            set.addAll(first);
        }
        if (second != null) {
            set.addAll(second);
        }
        return new ArrayList<>(set);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
