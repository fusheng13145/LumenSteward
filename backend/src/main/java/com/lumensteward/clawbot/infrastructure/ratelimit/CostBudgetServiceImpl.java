package com.lumensteward.clawbot.infrastructure.ratelimit;

import com.lumensteward.clawbot.application.config.ConfigKeys;
import com.lumensteward.clawbot.application.config.DynamicConfigService;
import com.lumensteward.clawbot.application.ratelimit.BudgetEvaluator;
import com.lumensteward.clawbot.application.ratelimit.BudgetEvaluator.BudgetStatus;
import com.lumensteward.clawbot.application.ratelimit.CostBudgetService;
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
 * {@link CostBudgetService} 的 Redis 实现（FR-20 ③）。
 *
 * <p>计数器：{@code cost:llm:daily:{date}}、{@code cost:llm:hourly:{dateHour}}。
 * 耗尽时置 {@code cost:degraded} 标志位，TTL 至次日零点实现自动恢复。
 * Redis 不可用时静默放行（不阻断对话，仅失去成本保护）。
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
    private final Clock clock;

    /**
     * Spring 装配用构造器（G-14）：时钟默认 UTC 系统时钟。
     *
     * @param redisTemplate 字符串 Redis 模板
     * @param dynamicConfig  动态配置（日预算，可为 null）
     */
    @Autowired
    public CostBudgetServiceImpl(StringRedisTemplate redisTemplate,
                                DynamicConfigService dynamicConfig) {
        this(redisTemplate, dynamicConfig, Clock.systemUTC());
    }

    /**
     * 测试友好构造（可注入固定时钟）。
     *
     * @param redisTemplate 字符串 Redis 模板
     * @param dynamicConfig  动态配置（日预算，可为 null）
     * @param clock          时钟
     */
    CostBudgetServiceImpl(StringRedisTemplate redisTemplate,
                         DynamicConfigService dynamicConfig,
                         Clock clock) {
        this.redisTemplate = redisTemplate;
        this.dynamicConfig = dynamicConfig;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    @Override
    public void recordLlmCall(int tokens) {
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
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey("cost:degraded"));
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public int dailyUsagePercent() {
        try {
            String raw = redisTemplate.opsForValue().get("cost:llm:daily:" + LocalDate.now(clock));
            long used = raw == null ? 0L : Long.parseLong(raw);
            return BudgetEvaluator.percent(used, dailyBudget());
        } catch (RuntimeException e) {
            return 0;
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
