package com.lumensteward.clawbot.infrastructure.cache;

import com.lumensteward.clawbot.domain.context.ContextStore;
import com.lumensteward.clawbot.infrastructure.client.llm.dto.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link ContextStore} 的 Redis 实现（8.3：{@code conv:{openid}}，TTL 24h）。
 *
 * <p>前缀分域：会话上下文使用 {@code conv:} 命名空间，与 {@code wx:token:} 严格分离
 * （9.4.6(1)）。Redis 故障时全部方法降级为安全空操作，主链路继续（SRS 9.5）。
 */
@Repository
public class RedisContextStore implements ContextStore {

    private static final Logger log = LoggerFactory.getLogger(RedisContextStore.class);

    /** key 前缀（8.3）。 */
    public static final String KEY_PREFIX = "conv:";
    /** 上下文 TTL（24h）。 */
    public static final Duration TTL = Duration.ofHours(24);
    /** 上下文最大保留条数（防无限增长，主控仍为 token 预算）。 */
    private static final int MAX_MESSAGES = 200;

    private final RedisTemplate<String, Object> redisTemplate;

    /**
     * 构造器注入（G-14）。
     *
     * @param redisTemplate 通用 Redis 模板（key=String，value=JSON）
     */
    public RedisContextStore(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<ChatMessage> load(String openid) {
        if (openid == null || openid.isBlank()) {
            return List.of();
        }
        try {
            Object value = redisTemplate.opsForValue().get(KEY_PREFIX + openid);
            if (value instanceof List<?> list) {
                List<ChatMessage> result = new ArrayList<>();
                for (Object item : list) {
                    if (item instanceof ChatMessage message) {
                        result.add(message);
                    }
                }
                return result;
            }
            return List.of();
        } catch (RuntimeException e) {
            log.warn("载入上下文失败（无状态降级）: err={}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public void append(String openid, ChatMessage message) {
        if (message == null) {
            return;
        }
        appendAll(openid, List.of(message));
    }

    @Override
    public void appendAll(String openid, List<ChatMessage> messages) {
        if (openid == null || openid.isBlank() || messages == null || messages.isEmpty()) {
            return;
        }
        try {
            List<ChatMessage> current = new ArrayList<>(load(openid));
            current.addAll(messages);
            if (current.size() > MAX_MESSAGES) {
                current = new ArrayList<>(current.subList(current.size() - MAX_MESSAGES, current.size()));
            }
            redisTemplate.opsForValue().set(KEY_PREFIX + openid, current, TTL);
        } catch (RuntimeException e) {
            log.warn("写上下文失败（无状态降级）: err={}", e.getMessage());
        }
    }

    @Override
    public void clear(String openid) {
        if (openid == null || openid.isBlank()) {
            return;
        }
        try {
            redisTemplate.delete(KEY_PREFIX + openid);
        } catch (RuntimeException e) {
            log.warn("清空上下文失败: err={}", e.getMessage());
        }
    }

    @Override
    public boolean isAvailable() {
        try {
            RedisConnection connection = redisTemplate.getConnectionFactory() == null
                    ? null : redisTemplate.getConnectionFactory().getConnection();
            if (connection == null) {
                return false;
            }
            try {
                connection.ping();
                return true;
            } finally {
                connection.close();
            }
        } catch (RuntimeException e) {
            return false;
        }
    }
}
