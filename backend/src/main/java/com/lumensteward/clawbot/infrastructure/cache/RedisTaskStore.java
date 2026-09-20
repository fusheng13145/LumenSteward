package com.lumensteward.clawbot.infrastructure.cache;

import com.fasterxml.jackson.core.type.TypeReference;
import com.lumensteward.clawbot.common.util.JsonUtils;
import com.lumensteward.clawbot.domain.model.TaskContext;
import com.lumensteward.clawbot.domain.task.TaskStore;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * {@link TaskStore} 的 Redis 实现（SRS FR-24 / BR-34，Redisson）。
 *
 * <p>键名 {@code task:{openid}}，值=任务栈 JSON，随写入刷新 TTL（默认 10 分钟，8.3 前缀分域）。
 * 与 {@code conv:{openid}} 严格分离：任务状态绝不进入模型上下文。
 *
 * <p><b>故障降级（SRS 9.5）：</b>Redisson 客户端为空或任何运行时异常一律安全降级——
 * {@link #load} 返回空、{@link #save} 返回 {@code false}、{@link #delete} 静默，
 * 上层据此将任务视为取消，主链路不受影响。
 */
@Repository
public class RedisTaskStore implements TaskStore {

    private static final Logger log = LoggerFactory.getLogger(RedisTaskStore.class);

    /** key 前缀（8.3 前缀分域），完整键为 {@code task:{openid}}。 */
    public static final String KEY_PREFIX = "task:";

    /** 任务活跃态 TTL（FR-24：默认 10 分钟无交互即过期）。 */
    public static final Duration TTL = Duration.ofMinutes(10);

    private static final TypeReference<List<TaskContext>> STACK_TYPE = new TypeReference<>() {
    };

    private final RedissonClient redissonClient;

    /**
     * 构造器注入（G-14）。
     *
     * @param redissonClient Redisson 客户端（Bean 恒在；为空视作不可用）
     */
    public RedisTaskStore(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /**
     * 计算某用户的活跃任务键。
     *
     * @param openid 用户标识
     * @return {@code task:{openid}}
     */
    public static String key(String openid) {
        return KEY_PREFIX + openid;
    }

    @Override
    public boolean save(String openid, List<TaskContext> tasks, Duration ttl) {
        if (redissonClient == null || openid == null || openid.isBlank() || tasks == null) {
            return false;
        }
        try {
            String json = JsonUtils.toJson(tasks);
            if (json == null) {
                return false;
            }
            Duration effective = (ttl == null || ttl.isZero() || ttl.isNegative()) ? TTL : ttl;
            bucket(openid).set(json, effective);
            return true;
        } catch (RuntimeException e) {
            log.warn("写入任务活跃态失败（视为任务取消）: err={}", e.getMessage());
            return false;
        }
    }

    @Override
    public Optional<List<TaskContext>> load(String openid) {
        if (redissonClient == null || openid == null || openid.isBlank()) {
            return Optional.empty();
        }
        try {
            Object value = bucket(openid).get();
            if (!(value instanceof String json) || json.isBlank()) {
                return Optional.empty();
            }
            List<TaskContext> tasks = JsonUtils.fromJson(json, STACK_TYPE);
            if (tasks == null || tasks.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(tasks);
        } catch (RuntimeException e) {
            log.warn("载入任务活跃态失败（视为任务取消）: err={}", e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void delete(String openid) {
        if (redissonClient == null || openid == null || openid.isBlank()) {
            return;
        }
        try {
            bucket(openid).delete();
        } catch (RuntimeException e) {
            log.warn("清理任务活跃态失败: err={}", e.getMessage());
        }
    }

    @Override
    public boolean exists(String openid) {
        if (redissonClient == null || openid == null || openid.isBlank()) {
            return false;
        }
        try {
            return bucket(openid).get() != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private RBucket<String> bucket(String openid) {
        return redissonClient.getBucket(key(openid));
    }
}
