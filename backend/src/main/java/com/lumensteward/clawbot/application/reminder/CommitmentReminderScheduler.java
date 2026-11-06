package com.lumensteward.clawbot.application.reminder;

import com.lumensteward.clawbot.application.config.ConfigKeys;
import com.lumensteward.clawbot.application.config.DynamicConfigService;
import com.lumensteward.clawbot.application.wechat.WechatMessageService;
import com.lumensteward.clawbot.common.util.MaskUtils;
import com.lumensteward.clawbot.domain.reminder.CommitmentRecord;
import com.lumensteward.clawbot.domain.reminder.CommitmentStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 到期提醒调度器（迭代 4 W7/W8 最小提醒切片，D2 的 P4 承担者）。
 *
 * <p>每 30s 扫描到期承诺（PENDING 且 due_at ≤ now）→ 预算检查 → 投递闩 → 客服消息。
 * 三道护栏：
 * <ol>
 *   <li><b>开关</b>：{@code reminder.enabled}（默认开；登记侧另有 memory.growth 双闸门）；</li>
 *   <li><b>条数预算</b>：{@code reminder.max-per-48h}（默认 5）——客服消息「一次交互 48h 内
 *       至多 5 条」频控的<b>保守近似</b>：按 openid 固定 48h 窗口计数（Redis INCR + 首写 TTL），
 *       超限的承诺留 PENDING 下轮重试（宁晚勿丢、宁少勿超）；Redis 不可用时预算退化为不限制
 *       （fail-open，与限流同口径，投递本身仍受通道约束）；</li>
 *   <li><b>投递闩</b>：PENDING → DELIVERED 条件 UPDATE，抢到的一方才外发——至多一次，
 *       与长任务通道（W15）同一幂等口径。</li>
 * </ol>
 * <p>已知边界（如实声明）：投递走 {@code pushAsync} 不落 {@code wx_message}（占位通道口径），
 * 送达凭 Mock 出站日志与 Redis 计数可查；闩置位后若发送失败不自动补发——「不重复下发」优先。
 * 取消/续期属 W8 全动词集（D3），本切片无取消路径。
 */
@Component
public class CommitmentReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(CommitmentReminderScheduler.class);

    /** 扫描周期（ms，照 GrayCircuitBreaker 常量式先例；调整需重启）。 */
    static final long SCAN_INTERVAL_MS = 30_000L;

    /** 单次扫描上限（防到期积压一次性淹没通道）。 */
    private static final int SCAN_LIMIT = 50;

    /** 48h 预算计数键前缀（完整键 {@code rem:48h:{openid}}）。 */
    public static final String BUDGET_KEY_PREFIX = "rem:48h:";

    private static final Duration BUDGET_WINDOW = Duration.ofHours(48);

    private final CommitmentStore commitmentStore;
    private final WechatMessageService wechatMessageService;
    private final DynamicConfigService dynamicConfig;
    private final StringRedisTemplate redisTemplate;

    /**
     * Spring 装配用构造器（G-14）。
     *
     * @param commitmentStore     承诺存储端口
     * @param wechatMessageService 客服消息通道（占位口径：仅发送不落库）
     * @param dynamicConfig       动态配置源（可为 null，此时用内置默认）
     * @param redisTemplate       Redis 计数（48h 预算；可为 null，此时预算退化为不限制）
     */
    @Autowired
    public CommitmentReminderScheduler(CommitmentStore commitmentStore,
                                       WechatMessageService wechatMessageService,
                                       DynamicConfigService dynamicConfig,
                                       StringRedisTemplate redisTemplate) {
        this.commitmentStore = commitmentStore;
        this.wechatMessageService = wechatMessageService;
        this.dynamicConfig = dynamicConfig;
        this.redisTemplate = redisTemplate;
    }

    /** 周期扫描：到点 → 预算 → 闩 → 投递。异常整体捕获，不冒泡到调度线程池。 */
    @Scheduled(initialDelay = SCAN_INTERVAL_MS, fixedDelay = SCAN_INTERVAL_MS)
    public void scan() {
        try {
            if (!enabled()) {
                return;
            }
            List<CommitmentRecord> due = commitmentStore.findDue(LocalDateTime.now(), SCAN_LIMIT);
            for (CommitmentRecord commitment : due) {
                deliver(commitment);
            }
        } catch (RuntimeException e) {
            log.warn("到期提醒扫描异常（下一轮重试）: {}", e.getMessage());
        }
    }

    /** 单条投递：预算 → 闩 → 外发。 */
    void deliver(CommitmentRecord commitment) {
        if (!withinBudget(commitment.openid())) {
            log.info("到期提醒因 48h 预算暂缓 id={} openid={}（留 PENDING 下轮重试）",
                    commitment.id(), MaskUtils.openid(commitment.openid()));
            return;
        }
        if (!commitmentStore.markDelivered(commitment.id())) {
            return;
        }
        String text = "到点提醒：你之前托付的事——「" + commitment.content() + "」——现在正是时候，要开始吗？";
        try {
            wechatMessageService.pushAsync(commitment.openid(), text);
            log.info("到期提醒已投递 id={} openid={} dueAt={} 内容={}",
                    commitment.id(), MaskUtils.openid(commitment.openid()),
                    commitment.dueAt(), commitment.content());
        } catch (RuntimeException e) {
            log.warn("到期提醒投递异常 id={}（闩已置位，不重复下发）: {}", commitment.id(), e.getMessage());
        }
    }

    /** 48h 固定窗口条数预算：首写设 TTL；Redis 不可用按不限制处理（fail-open）。 */
    private boolean withinBudget(String openid) {
        int max = maxPer48h();
        if (redisTemplate == null) {
            return true;
        }
        try {
            String key = BUDGET_KEY_PREFIX + openid;
            String used = redisTemplate.opsForValue().get(key);
            if (used == null) {
                redisTemplate.opsForValue().set(key, "1", BUDGET_WINDOW);
                return max > 0;
            }
            long current = Long.parseLong(used);
            if (current >= max) {
                return false;
            }
            redisTemplate.opsForValue().increment(key);
            return true;
        } catch (RuntimeException e) {
            log.warn("提醒预算读取失败（按不限制处理）: {}", e.getMessage());
            return true;
        }
    }

    private boolean enabled() {
        return dynamicConfig == null || dynamicConfig.getBoolean(ConfigKeys.REMINDER_ENABLED, true);
    }

    private int maxPer48h() {
        return dynamicConfig == null ? 5
                : Math.max(1, dynamicConfig.getInt(ConfigKeys.REMINDER_MAX_PER_48H, 5));
    }
}
