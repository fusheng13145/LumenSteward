package com.lumensteward.clawbot.infrastructure.cache;

import com.lumensteward.clawbot.infrastructure.config.properties.WechatProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * {@link DedupService} 的 Redis 实现（8.3：{@code dedup:msg:{msgId}}，TTL 300s）。
 *
 * <p>Redis 不可用时 Fail-Open（视为首次出现并放行）：去重是「防重复处理」的保护，而非正确性前提；
 * 若去重不可用即拒绝全部消息，将造成不可用，违背 BR-29「保护而非惩罚」。
 */
@Service
public class RedisDedupService implements DedupService {

    private static final Logger log = LoggerFactory.getLogger(RedisDedupService.class);

    /** key 前缀（8.3）。 */
    public static final String KEY_PREFIX = "dedup:msg:";

    private final StringRedisTemplate redisTemplate;
    private final WechatProperties properties;

    /**
     * 构造器注入（G-14）。
     *
     * @param redisTemplate 字符串 Redis 模板
     * @param properties    微信配置（TTL）
     */
    public RedisDedupService(StringRedisTemplate redisTemplate, WechatProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    @Override
    public boolean markIfAbsent(String msgId) {
        if (msgId == null || msgId.isBlank()) {
            // 无 MsgId 无法去重，放行（微信事件类消息可能无 MsgId）
            return true;
        }
        long ttl = properties.dedupTtlSeconds() <= 0 ? 300L : properties.dedupTtlSeconds();
        try {
            Boolean created = redisTemplate.opsForValue()
                    .setIfAbsent(KEY_PREFIX + msgId, "1", Duration.ofSeconds(ttl));
            return Boolean.TRUE.equals(created);
        } catch (RuntimeException e) {
            log.warn("Redis 去重不可用，Fail-Open 放行: err={}", e.getMessage());
            return true;
        }
    }
}
