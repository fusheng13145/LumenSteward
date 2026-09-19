package com.lumensteward.clawbot.infrastructure.cache;

/**
 * 限流服务（架构 5.4 / SRS FR-20，BR-29）。
 *
 * <p>维度：用户级令牌桶（20/分钟、300/小时）+ 来源 IP 洪泛防护（300/分钟）。
 * 限流是保护措施而非惩罚：Redis 不可用时 Fail-Open（BR-29）。白名单内 openid 豁免。
 *
 * <p>决策结果以 {@link RateLimitDecision} 表达，便于将超限事件落 {@code log_rate_limit}（FR-17 ④ 可检索）。
 */
public interface RateLimitService {

    /**
     * 尝试获取配额。
     *
     * @param openid 用户标识（可空）
     * @param ip     来源 IP（可空）
     * @return 决策：{@code ALLOWED} 放行，其余为对应维度超限
     */
    RateLimitDecision tryAcquire(String openid, String ip);
}
