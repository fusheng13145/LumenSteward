package com.lumensteward.clawbot.infrastructure.ratelimit;

import com.lumensteward.clawbot.application.config.ConfigKeys;
import com.lumensteward.clawbot.application.config.DynamicConfigService;
import com.lumensteward.clawbot.application.ratelimit.BudgetEvaluator;
import com.lumensteward.clawbot.application.ratelimit.BudgetEvaluator.BudgetStatus;
import com.lumensteward.clawbot.application.ratelimit.CostBudgetService;
import com.lumensteward.clawbot.infrastructure.persistence.service.LlmCallLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * {@link CostBudgetService} 的 Redis 计数 + DB 明细实现（FR-20 ③ / B-4）。
 *
 * <p>计数器：{@code cost:llm:daily:{date}}、{@code cost:llm:hourly:{dateHour}}。
 * 日桶 TTL 至次日零点，故预算耗尽会在零点自动恢复。
 * {@link #isDegraded()} <b>按当前用量与当前预算实时判定</b>，不读 {@code cost:degraded}
 * 标志位——那个键只用于「同一状态只告警一次」的日志去重。若改读标志位，运维在当天抬高预算后
 * 机器人仍会一路回「额度已用完」到午夜（迭代 4 W5 实测到的缺陷，见手册 §7.5）。
 * Redis 不可用时静默放行（不阻断对话，仅失去成本保护）。
 *
 * <p><b>日界口径</b>：时钟取<b>系统默认时区</b>而非 UTC。{@code log_llm_call.created_at} 由
 * {@code MyMetaObjectHandler} 按本地时间写入，日桶若按 UTC 日期分则「今日」两套口径会在
 * 本地 08:00（UTC+8）错位，FR-17 AC① 要求的明细/计数互查即失效；「次日零点自动恢复」也会
 * 提前 8 小时发生。
 */
@Service
public class CostBudgetServiceImpl implements CostBudgetService {

    private static final Logger log = LoggerFactory.getLogger(CostBudgetServiceImpl.class);

    /** 默认日 token 预算。 */
    public static final int DEFAULT_DAILY_BUDGET = 200_000;
    /** 告警标志位有效期（天）。 */
    private static final long WARN_FLAG_TTL_DAYS = 1L;

    private final StringRedisTemplate redisTemplate;
    private final DynamicConfigService dynamicConfig;
    private final LlmCallLogService llmCallLogService;
    private final Clock clock;

    /**
     * Spring 装配用构造器（G-14）：时钟默认系统默认时区（见类注释的日界口径）。
     *
     * @param redisTemplate     字符串 Redis 模板
     * @param dynamicConfig     动态配置（日预算，可为 null）
     * @param llmCallLogService LLM 调用明细写入服务（可为 null，此时只计数不落明细）
     */
    @Autowired
    public CostBudgetServiceImpl(StringRedisTemplate redisTemplate,
                                DynamicConfigService dynamicConfig,
                                LlmCallLogService llmCallLogService) {
        this(redisTemplate, dynamicConfig, llmCallLogService, Clock.systemDefaultZone());
    }

    /**
     * 测试友好构造（可注入固定时钟）。
     *
     * @param redisTemplate     字符串 Redis 模板
     * @param dynamicConfig     动态配置（日预算，可为 null）
     * @param llmCallLogService LLM 调用明细写入服务（可为 null）
     * @param clock             时钟
     */
    CostBudgetServiceImpl(StringRedisTemplate redisTemplate,
                         DynamicConfigService dynamicConfig,
                         LlmCallLogService llmCallLogService,
                         Clock clock) {
        this.redisTemplate = redisTemplate;
        this.dynamicConfig = dynamicConfig;
        this.llmCallLogService = llmCallLogService;
        this.clock = clock == null ? Clock.systemDefaultZone() : clock;
    }

    @Override
    public void recordLlmCall(LlmCallUsage usage) {
        if (usage == null || usage.purpose() == null) {
            return;
        }
        // 两条写入口径分开兜底：DB 故障不应让预算漏计，Redis 故障也不应让明细丢失
        recordDetail(usage);
        accumulate(usage.totalTokens());
    }

    private void recordDetail(LlmCallUsage usage) {
        if (llmCallLogService == null) {
            return;
        }
        try {
            llmCallLogService.record(usage);
        } catch (RuntimeException e) {
            log.warn("LLM 调用明细写入失败（忽略，计数继续）: err={}", e.getMessage());
        }
    }

    private void accumulate(int tokens) {
        if (tokens <= 0) {
            return;
        }
        try {
            LocalDateTime now = LocalDateTime.now(clock);
            LocalDate date = now.toLocalDate();
            String dailyKey = "cost:llm:daily:" + date;
            String hourlyKey = "cost:llm:hourly:" + date + ":" + now.getHour();

            Long dailyUsed = redisTemplate.opsForValue().increment(dailyKey, tokens);
            if (dailyUsed != null && dailyUsed.equals((long) tokens)) {
                redisTemplate.expire(dailyKey, secondsUntilNextMidnight(now), java.util.concurrent.TimeUnit.SECONDS);
            }
            redisTemplate.opsForValue().increment(hourlyKey, tokens);
            redisTemplate.expire(hourlyKey, 2, java.util.concurrent.TimeUnit.HOURS);

            long used = dailyUsed == null ? tokens : dailyUsed;
            long budget = dailyBudget();
            BudgetStatus status = BudgetEvaluator.evaluate(used, budget);
            if (status == BudgetStatus.DEGRADED && !Boolean.TRUE.equals(redisTemplate.hasKey("cost:degraded"))) {
                redisTemplate.opsForValue().set("cost:degraded", "1",
                        secondsUntilNextMidnight(now), java.util.concurrent.TimeUnit.SECONDS);
                log.warn("LLM 日预算耗尽，进入降级模式（仅基础回复），used={} budget={}", used, budget);
            } else if (status == BudgetStatus.WARN
                    && !Boolean.TRUE.equals(redisTemplate.hasKey("cost:warn:" + date))) {
                redisTemplate.opsForValue().set("cost:warn:" + date, "1",
                        WARN_FLAG_TTL_DAYS, java.util.concurrent.TimeUnit.DAYS);
                log.warn("LLM 日预算达 80% 告警，used={} budget={}", used, budget);
            }
        } catch (RuntimeException e) {
            log.warn("成本预算统计失败（忽略，放行）: err={}", e.getMessage());
        }
    }

    @Override
    public boolean isDegraded() {
        return BudgetEvaluator.evaluate(dailyUsed(), dailyBudget()) == BudgetStatus.DEGRADED;
    }

    @Override
    public int dailyUsagePercent() {
        return BudgetEvaluator.percent(dailyUsed(), dailyBudget());
    }

    @Override
    public long dailyTokenBudget() {
        return dailyBudget();
    }

    /**
     * 读今日计数桶；Redis 不可用或值非法时按 0 处理（放行，不阻断对话）。
     *
     * @return 今日已用 token
     */
    private long dailyUsed() {
        try {
            String raw = redisTemplate.opsForValue().get("cost:llm:daily:" + LocalDate.now(clock));
            return raw == null ? 0L : Long.parseLong(raw);
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    private long dailyBudget() {
        return dynamicConfig == null ? DEFAULT_DAILY_BUDGET
                : dynamicConfig.getLong(ConfigKeys.RATE_LIMIT_DAILY_TOKEN_BUDGET, DEFAULT_DAILY_BUDGET);
    }

    private long secondsUntilNextMidnight(LocalDateTime now) {
        LocalDateTime nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay();
        return Math.max(1, now.until(nextMidnight, ChronoUnit.SECONDS));
    }
}
