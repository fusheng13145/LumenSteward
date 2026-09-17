package com.lumensteward.clawbot.infrastructure.cache;

/**
 * 限流服务（架构 5.4 / SRS FR-20，BR-29）。
 *
 * <p>用户维度 {@code rl:user:{openid}} 与 IP 维度 {@code rl:ip:{ip}} 双键限流（8.3）。
 * 限流是保护措施而非惩罚：Redis 不可用时 Fail-Open。
 */
public interface RateLimitService {

    /**
     * 尝试获取配额。
     *
     * @param openid 用户标识（可空）
     * @param ip     来源 IP（可空）
     * @return true 允许；false 超限
     */
    boolean tryAcquire(String openid, String ip);
}
