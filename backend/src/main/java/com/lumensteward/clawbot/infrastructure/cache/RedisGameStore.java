package com.lumensteward.clawbot.infrastructure.cache;

import com.lumensteward.clawbot.common.util.JsonUtils;
import com.lumensteward.clawbot.domain.game.GameSession;
import com.lumensteward.clawbot.domain.game.GameStore;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * {@link GameStore} 的 Redis 实现（迭代 4 W19，Redisson）。
 *
 * <p>键名 {@code game:{openid}}（8.3 前缀分域，单用户同时至多一局，开局覆盖即换局），
 * 值=会话 JSON，TTL 24h——<b>与对话上下文 {@code conv:{openid}} / 识图缓存 {@code img:{openid}}
 * 同口径</b>：局随上下文窗口一起自然过期，过期即「对局结束」，工具按会话缺失如实回应。
 *
 * <p><b>故障降级（对齐 {@link RedisRecentImageStore}）：</b>Redisson 客户端为空或任何运行时
 * 异常一律安全降级——{@link #find} 返回空（游戏工具本轮不可见）、{@link #save}/{@link #delete}
 * 返回 {@code false}（开局/关局如实提示），主对话链路不受影响。
 */
@Repository
public class RedisGameStore implements GameStore {

    private static final Logger log = LoggerFactory.getLogger(RedisGameStore.class);

    /** key 前缀（8.3 前缀分域），完整键为 {@code game:{openid}}。 */
    public static final String KEY_PREFIX = "game:";

    /** 桌游会话 TTL（与上下文窗口同口径：24h）。 */
    public static final java.time.Duration TTL = java.time.Duration.ofHours(24);

    private final RedissonClient redissonClient;

    /**
     * 构造器注入（G-14）。
     *
     * @param redissonClient Redisson 客户端（Bean 恒在；为空视作不可用）
     */
    public RedisGameStore(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /** 完整键名（供测试断言）。 */
    public static String key(String openid) {
        return KEY_PREFIX + openid;
    }

    @Override
    public boolean save(String openid, GameSession session) {
        if (redissonClient == null || openid == null || openid.isBlank() || session == null) {
            return false;
        }
        try {
            RBucket<String> bucket = redissonClient.getBucket(key(openid));
            bucket.set(JsonUtils.toJson(session), TTL);
            return true;
        } catch (RuntimeException e) {
            log.warn("桌游会话写入失败（忽略，按无进行中局处理）: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public Optional<GameSession> find(String openid) {
        if (redissonClient == null || openid == null || openid.isBlank()) {
            return Optional.empty();
        }
        try {
            RBucket<String> bucket = redissonClient.getBucket(key(openid));
            String json = bucket.get();
            return json == null || json.isBlank()
                    ? Optional.empty() : Optional.of(JsonUtils.mapper().readValue(json, GameSession.class));
        } catch (Exception e) {
            log.warn("桌游会话读取失败（按无进行中局处理）: {}", e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public boolean delete(String openid) {
        if (redissonClient == null || openid == null || openid.isBlank()) {
            return false;
        }
        try {
            return redissonClient.getBucket(key(openid)).delete();
        } catch (RuntimeException e) {
            log.warn("桌游会话删除失败（按无进行中局处理）: {}", e.getMessage());
            return false;
        }
    }
}
