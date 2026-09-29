package com.lumensteward.clawbot.infrastructure.cache;

import com.lumensteward.clawbot.common.util.JsonUtils;
import com.lumensteward.clawbot.domain.context.RecentImageStore;
import com.lumensteward.clawbot.domain.model.RecentImage;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * {@link RecentImageStore} 的 Redis 实现（迭代 4 W11，Redisson）。
 *
 * <p>键名 {@code img:{openid}}（8.3 前缀分域），值=识图结论 JSON，
 * TTL 24h——<b>与对话上下文 {@code conv:{openid}} 同口径</b>（W11 交付判据：
 * "过期后工具消失"，过期语义即上下文窗口语义）。重复识别 {@code set} 覆盖 = 换图即换缓存。
 *
 * <p><b>故障降级（对齐 {@link RedisTaskStore}）：</b>Redisson 客户端为空或任何运行时异常
 * 一律安全降级——{@link #find} 返回空、{@link #save} 返回 {@code false}，主链路不受影响。
 */
@Repository
public class RedisRecentImageStore implements RecentImageStore {

    private static final Logger log = LoggerFactory.getLogger(RedisRecentImageStore.class);

    /** key 前缀（8.3 前缀分域），完整键为 {@code img:{openid}}。 */
    public static final String KEY_PREFIX = "img:";

    /** 识图缓存 TTL（与上下文窗口同口径：24h）。 */
    public static final java.time.Duration TTL = java.time.Duration.ofHours(24);

    private final RedissonClient redissonClient;

    /**
     * 构造器注入（G-14）。
     *
     * @param redissonClient Redisson 客户端（Bean 恒在；为空视作不可用）
     */
    public RedisRecentImageStore(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /** 完整键名（供测试断言）。 */
    public static String key(String openid) {
        return KEY_PREFIX + openid;
    }

    @Override
    public boolean save(String openid, RecentImage image) {
        if (redissonClient == null || openid == null || openid.isBlank() || image == null) {
            return false;
        }
        try {
            RBucket<String> bucket = redissonClient.getBucket(key(openid));
            bucket.set(JsonUtils.toJson(image), TTL);
            return true;
        } catch (RuntimeException e) {
            log.warn("识图缓存写入失败（忽略，追问工具将不可见）: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public Optional<RecentImage> find(String openid) {
        if (redissonClient == null || openid == null || openid.isBlank()) {
            return Optional.empty();
        }
        try {
            RBucket<String> bucket = redissonClient.getBucket(key(openid));
            String json = bucket.get();
            return json == null || json.isBlank()
                    ? Optional.empty() : Optional.of(JsonUtils.mapper().readValue(json, RecentImage.class));
        } catch (Exception e) {
            log.warn("识图缓存读取失败（按无缓存处理）: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
