package com.lumensteward.clawbot.infrastructure.client.wechat;

import com.fasterxml.jackson.databind.JsonNode;
import com.lumensteward.clawbot.common.util.JsonUtils;
import com.lumensteward.clawbot.common.util.MaskUtils;
import com.lumensteward.clawbot.infrastructure.config.properties.WechatProperties;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * {@link WechatTokenService} 实现（SRS 9.4.6(1)，G-20）。
 *
 * <p>缓存 key {@code wx:token:{appId}}（前缀分域），提前过期 7000s；回源以 Redisson 锁
 * {@code wx:token:lock:{appId}} 防击穿；到期时间另存 {@code wx:token:expire:{appId}} 供审计。
 *
 * <p>Mock 模式下仍完整走「缓存 → 锁 → 回源（合成 token）」流程，使 G-20 逻辑在离线时可被验证。
 */
@Service
public class WechatTokenServiceImpl implements WechatTokenService {

    private static final Logger log = LoggerFactory.getLogger(WechatTokenServiceImpl.class);

    /** 缓存 key 前缀（前缀分域，9.4.6(1)）。 */
    private static final String TOKEN_KEY_PREFIX = "wx:token:";
    /** 到期时间 key 前缀。 */
    private static final String EXPIRE_KEY_PREFIX = "wx:token:expire:";
    /** 分布式锁 key 前缀。 */
    private static final String LOCK_KEY_PREFIX = "wx:token:lock:";
    /** 平台有效期（秒）。 */
    private static final long PLATFORM_TTL_SECONDS = 7200L;
    /** 缓存提前过期（秒）：短于平台有效期，规避边界窗口。 */
    private static final long EARLY_EXPIRE_SECONDS = 7000L;
    /** 锁等待/持有时间（秒）。 */
    private static final long LOCK_WAIT_SECONDS = 5L;

    private final StringRedisTemplate redisTemplate;
    private final RedissonClient redissonClient;
    private final WechatProperties properties;
    private final RestClient wechatRestClient;

    /**
     * 构造器注入（G-14）。
     *
     * @param redisTemplate  字符串 Redis 模板
     * @param redissonClient Redisson 客户端（分布式锁）
     * @param properties     微信配置
     * @param wechatRestClient 微信出站客户端
     */
    public WechatTokenServiceImpl(StringRedisTemplate redisTemplate, RedissonClient redissonClient,
                                  WechatProperties properties, RestClient wechatRestClient) {
        this.redisTemplate = redisTemplate;
        this.redissonClient = redissonClient;
        this.properties = properties;
        this.wechatRestClient = wechatRestClient;
    }

    @Override
    public String getAccessToken() {
        String appId = properties.appId();
        String cacheKey = TOKEN_KEY_PREFIX + appId;
        String cached = safeGet(cacheKey);
        if (cached != null && !cached.isBlank()) {
            return cached;
        }
        RLock lock = redissonClient.getLock(LOCK_KEY_PREFIX + appId);
        boolean locked = false;
        try {
            locked = lock.tryLock(LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
            // 双重检查：可能在等待锁期间已被其他线程回源填充
            String doubleChecked = safeGet(cacheKey);
            if (doubleChecked != null && !doubleChecked.isBlank()) {
                return doubleChecked;
            }
            return fetchAndCache(appId, cacheKey);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("等待 token 分布式锁被中断，回退直接回源");
            return fetchAndCache(appId, cacheKey);
        } finally {
            if (locked && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    public String evictAndRefresh() {
        String appId = properties.appId();
        safeDelete(TOKEN_KEY_PREFIX + appId);
        // 单次失效重试：仅回源一次，避免放大
        return fetchAndCache(appId, TOKEN_KEY_PREFIX + appId);
    }

    private String fetchAndCache(String appId, String cacheKey) {
        long nowEpoch = System.currentTimeMillis() / 1000L;
        String token = fetchFromPlatform(appId);
        if (token == null || token.isBlank()) {
            log.warn("微信 access_token 回源失败，返回空（调用方应降级）");
            return null;
        }
        safeSet(cacheKey, token, Duration.ofSeconds(EARLY_EXPIRE_SECONDS));
        safeSet(EXPIRE_KEY_PREFIX + appId, String.valueOf(nowEpoch + PLATFORM_TTL_SECONDS),
                Duration.ofSeconds(EARLY_EXPIRE_SECONDS));
        log.info("微信 access_token 已回源并缓存（提前过期 {}s）", EARLY_EXPIRE_SECONDS);
        return token;
    }

    private String fetchFromPlatform(String appId) {
        if (properties.mockEnabled()) {
            return "mock-access-token-" + (appId == null || appId.isBlank() ? "default" : appId);
        }
        // real：调用 /cgi-bin/token
        try {
            String raw = wechatRestClient.get()
                    .uri(uriBuilder -> uriBuilder.path("/cgi-bin/token")
                            .queryParam("grant_type", "client_credential")
                            .queryParam("appid", properties.appId())
                            .queryParam("secret", properties.appSecret())
                            .build())
                    .retrieve()
                    .body(String.class);
            JsonNode root = JsonUtils.readTree(raw);
            if (root != null && root.hasNonNull("access_token")) {
                return root.get("access_token").asText();
            }
            log.warn("微信 token 响应异常: {}", raw == null ? "null" : raw.replaceAll("secret=\\S+", "secret=****"));
            return null;
        } catch (RuntimeException e) {
            log.warn("微信 token 回源失败: {}", e.getMessage());
            return null;
        }
    }

    private String safeGet(String key) {
        try {
            return redisTemplate.opsForValue().get(key);
        } catch (RuntimeException e) {
            log.warn("Redis 读取失败（token 缓存降级）: key={} err={}", key, e.getMessage());
            return null;
        }
    }

    private void safeSet(String key, String value, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(key, value, ttl);
        } catch (RuntimeException e) {
            log.warn("Redis 写入失败（token 缓存降级）: key={} err={}", key, e.getMessage());
        }
    }

    private void safeDelete(String key) {
        try {
            redisTemplate.delete(key);
        } catch (RuntimeException e) {
            log.warn("Redis 删除失败: key={} err={}", key, e.getMessage());
        }
    }

    /** 暴露密钥脱敏（供排障日志复用）。 */
    @SuppressWarnings("unused")
    private String maskedSecret() {
        return MaskUtils.secret(properties.appSecret());
    }
}
