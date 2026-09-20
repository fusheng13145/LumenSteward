package com.lumensteward.clawbot.infrastructure.cache;

import com.lumensteward.clawbot.domain.model.TaskContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 任务活跃态 Redis 存储测试（SRS FR-24 / BR-34；键名 {@code task:{openid}}，TTL 10 分钟）。
 *
 * <p>以 Mockito 桩替代 Redisson，验证键名、写入 TTL、删除与「Redis 不可用即取消」的降级语义。
 */
class RedisTaskStoreTest {

    private static final String OPENID = "openid-task-key";

    @Test
    @DisplayName("键名为 task:{openid}，写入携带 10 分钟 TTL")
    @SuppressWarnings("unchecked")
    void shouldSaveUnderTaskKeyWithTtl() {
        RedissonClient client = mock(RedissonClient.class);
        RBucket<String> bucket = mock(RBucket.class);
        doReturn(bucket).when(client).getBucket(RedisTaskStore.key(OPENID));

        RedisTaskStore store = new RedisTaskStore(client);
        TaskContext task = TaskContext.newTask("query_express", List.of("tracking_no"),
                Instant.parse("2025-01-01T00:10:00Z"), Instant.parse("2025-01-01T00:00:00Z"));

        assertThat(RedisTaskStore.key(OPENID)).isEqualTo("task:openid-task-key");
        assertThat(RedisTaskStore.TTL).isEqualTo(Duration.ofMinutes(10));

        boolean saved = store.save(OPENID, List.of(task), RedisTaskStore.TTL);

        assertThat(saved).isTrue();
        verify(client).getBucket("task:openid-task-key");
        verify(bucket).set(anyString(), eq(RedisTaskStore.TTL));
    }

    @Test
    @DisplayName("删除清空活跃任务键（超时/放弃/完成路径）")
    @SuppressWarnings("unchecked")
    void shouldDeleteTaskKey() {
        RedissonClient client = mock(RedissonClient.class);
        RBucket<String> bucket = mock(RBucket.class);
        doReturn(bucket).when(client).getBucket(RedisTaskStore.key(OPENID));

        new RedisTaskStore(client).delete(OPENID);

        verify(bucket).delete();
    }

    @Test
    @DisplayName("load 反序列化任务栈；键不存在返回空")
    @SuppressWarnings("unchecked")
    void shouldLoadStack() {
        RedissonClient client = mock(RedissonClient.class);
        RBucket<String> bucket = mock(RBucket.class);
        doReturn(bucket).when(client).getBucket(RedisTaskStore.key(OPENID));
        TaskContext task = TaskContext.newTask("query_express", List.of("tracking_no"),
                Instant.parse("2025-01-01T00:10:00Z"), Instant.parse("2025-01-01T00:00:00Z"));
        when(bucket.get()).thenReturn(
                "[{\"taskType\":\"query_express\",\"requiredSlots\":[\"tracking_no\"],"
                        + "\"filledSlots\":{},\"expireAt\":\"2025-01-01T00:10:00Z\","
                        + "\"promptCount\":1,\"invalidAttempts\":0,\"createdAt\":\"2025-01-01T00:00:00Z\"}]");

        Optional<List<TaskContext>> loaded = new RedisTaskStore(client).load(OPENID);

        assertThat(loaded).isPresent();
        assertThat(loaded.get()).hasSize(1);
        assertThat(loaded.get().get(0).taskType()).isEqualTo("query_express");
        assertThat(loaded.get().get(0).requiredSlots()).containsExactly("tracking_no");
    }

    @Test
    @DisplayName("Redis 不可用（客户端为空）时安全降级：不抛异常、视为无任务")
    void shouldDegradeWhenRedisUnavailable() {
        RedisTaskStore store = new RedisTaskStore(null);

        assertThat(store.save(OPENID, List.of(), Duration.ofMinutes(1))).isFalse();
        assertThat(store.load(OPENID)).isEmpty();
        assertThat(store.exists(OPENID)).isFalse();
        store.delete(OPENID);
    }
}
