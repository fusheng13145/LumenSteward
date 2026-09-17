package com.lumensteward.clawbot.infrastructure.cache;

/**
 * JWT 黑名单（架构 5.4 / SRS FR-15 登出，8.3：{@code jwt:blacklist:{jti}}）。
 *
 * <p>登出时以 {@code jti} 写入黑名单，TTL 与剩余有效时间对齐；鉴权时查询命中即拒绝。
 */
public interface TokenBlacklistService {

    /**
     * 加入黑名单。
     *
     * @param jti        JWT 唯一标识
     * @param ttlSeconds 存活秒数（与 token 剩余有效期对齐）
     */
    void blacklist(String jti, long ttlSeconds);

    /**
     * 是否在黑名单。
     *
     * @param jti JWT 唯一标识
     * @return true 已失效
     */
    boolean isBlacklisted(String jti);
}
