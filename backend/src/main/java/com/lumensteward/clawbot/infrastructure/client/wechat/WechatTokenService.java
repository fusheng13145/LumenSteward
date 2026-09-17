package com.lumensteward.clawbot.infrastructure.client.wechat;

/**
 * 微信 access_token 服务（架构 5.1 / SRS 9.4.6(1)，G-20）。
 *
 * <p>硬性要求：
 * <ul>
 *   <li>前缀分域：缓存 key 为 {@code wx:token:{appId}}，与上下文缓存 {@code conv:{openid}}
 *       命名空间分离，不得混用；</li>
 *   <li>提前过期：缓存 TTL 短于平台有效期（平台 7200s → 缓存 7000s）；</li>
 *   <li>并发防击穿：Redisson 分布式锁保护回源；</li>
 *   <li>单次失效重试：平台返回失效错误码时清缓存并<b>仅</b>回源一次。</li>
 * </ul>
 */
public interface WechatTokenService {

    /**
     * 获取 access_token（缓存优先）。
     *
     * @return access_token
     */
    String getAccessToken();

    /**
     * 清除缓存并回源刷新一次（用于平台判定 token 失效时）。
     *
     * @return 新的 access_token
     */
    String evictAndRefresh();
}
