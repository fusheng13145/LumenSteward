package com.lumensteward.clawbot.infrastructure.cache;

/**
 * 限流决策（FR-20 / BR-29）。
 *
 * <p>区分维度以便监控可检索（FR-17 ④）：用户级令牌桶超限、IP 级洪泛防护超限。
 * 成本预算耗尽属于编排层降级（{@code CostBudgetService}），不在入站决策内。
 */
public enum RateLimitDecision {

    /** 允许放行。 */
    ALLOWED,
    /** 用户维度令牌桶超限（20/分钟、300/小时）。 */
    USER_FREQ,
    /** 来源 IP 维度洪泛防护超限。 */
    IP_FREQ
}
