package com.lumensteward.clawbot.infrastructure.cache;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.lumensteward.clawbot.infrastructure.persistence.entity.SysConfigEntity;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.SysConfigMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 配置读取缓存（架构 5.4 / SRS FR-18，BR-26：缓存优先 + 变更失效，读取 ≤ 100ms）。
 *
 * <p>三级读取：Caffeine 本地缓存（进程内，最快）→ Redis（{@code cfg:{key}}，跨实例）→
 * {@code sys_config} 表（回源）。{@link #invalidate(String)} 同时清除本地与 Redis，实现
 * 「变更即失效」。DB/Redis 任一不可用时逐级降级，不阻断读取。
 */
@Service
public class ConfigCacheService {

    private static final Logger log = LoggerFactory.getLogger(ConfigCacheService.class);

    /** Redis key 前缀（8.3）。 */
    public static final String KEY_PREFIX = "cfg:";

    private static final Duration REDIS_TTL = Duration.ofMinutes(30);

    private final SysConfigMapper sysConfigMapper;
    private final StringRedisTemplate redisTemplate;

    /** 本地一级缓存（TTL 60s 兜底）。 */
    private final Cache<String, String> localCache = Caffeine.newBuilder()
            .maximumSize(1000)
            .expireAfterWrite(Duration.ofSeconds(60))
            .build();

    /**
     * 构造器注入（G-14）。
     *
     * @param sysConfigMapper 配置 Mapper
     * @param redisTemplate   字符串 Redis 模板
     */
    public ConfigCacheService(SysConfigMapper sysConfigMapper, StringRedisTemplate redisTemplate) {
        this.sysConfigMapper = sysConfigMapper;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 读取配置值（缓存优先）。
     *
     * @param key 配置键（如 {@code llm.model}）
     * @return 配置值；不存在返回 null
     */
    public String get(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String local = localCache.getIfPresent(key);
        if (local != null) {
            return local;
        }
        String redisValue = readRedis(key);
        if (redisValue != null) {
            localCache.put(key, redisValue);
            return redisValue;
        }
        String dbValue = readDb(key);
        if (dbValue != null) {
            localCache.put(key, dbValue);
            writeRedis(key, dbValue);
        }
        return dbValue;
    }

    /**
     * 失效配置（变更后调用，本地 + Redis 同时清除）。
     *
     * @param key 配置键
     */
    public void invalidate(String key) {
        if (key == null || key.isBlank()) {
            return;
        }
        localCache.invalidate(key);
        try {
            redisTemplate.delete(KEY_PREFIX + key);
        } catch (RuntimeException e) {
            log.warn("Redis 失效配置失败: key={} err={}", key, e.getMessage());
        }
    }

    private String readRedis(String key) {
        try {
            return redisTemplate.opsForValue().get(KEY_PREFIX + key);
        } catch (RuntimeException e) {
            log.warn("Redis 读配置失败: key={} err={}", key, e.getMessage());
            return null;
        }
    }

    private void writeRedis(String key, String value) {
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + key, value, REDIS_TTL);
        } catch (RuntimeException e) {
            log.warn("Redis 写配置失败: key={} err={}", key, e.getMessage());
        }
    }

    private String readDb(String key) {
        try {
            SysConfigEntity entity = sysConfigMapper.selectOne(
                    Wrappers.<SysConfigEntity>lambdaQuery().eq(SysConfigEntity::getConfigKey, key).last("limit 1"));
            return entity == null ? null : entity.getConfigValue();
        } catch (RuntimeException e) {
            log.warn("DB 读配置失败: key={} err={}", key, e.getMessage());
            return null;
        }
    }
}
