package com.lumensteward.clawbot.infrastructure.cache;

import com.lumensteward.clawbot.common.util.JsonUtils;
import com.lumensteward.clawbot.domain.model.RecentImage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 最近识图缓存 Redis 存储测试（迭代 4 W11；键名 {@code img:{openid}}，TTL 24h 与上下文窗口同口径）。
 *
 * <p>以 Mockito 桩替代 Redisson，验证键名、写入 TTL、JSON 往返与「Redis 不可用即视为无缓存」的
 * fail-open 降级语义。
 */
class RedisRecentImageStoreTest {

    private static final String OPENID = "openid-img-key";

    private static RecentImage sample() {
        return new RecentImage("一只橘色的成年短毛猫", "pet", 0.9, Instant.parse("2026-09-28T12:00:00Z"));
    }

    @Test
    @DisplayName("键名为 img:{openid}，写入携带 24h TTL（与上下文窗口同口径）")
    @SuppressWarnings("unchecked")
    void shouldSaveUnderImageKeyWithContextWindowTtl() {
        RedissonClient client = mock(RedissonClient.class);
        RBucket<String> bucket = mock(RBucket.class);
        doReturn(bucket).when(client).getBucket(RedisRecentImageStore.key(OPENID));

        RedisRecentImageStore store = new RedisRecentImageStore(client);

        assertThat(RedisRecentImageStore.key(OPENID)).isEqualTo("img:openid-img-key");
        assertThat(RedisRecentImageStore.TTL).isEqualTo(Duration.ofHours(24));

        boolean saved = store.save(OPENID, sample());

        assertThat(saved).isTrue();
        verify(client).getBucket("img:openid-img-key");
        verify(bucket).set(anyString(), eq(RedisRecentImageStore.TTL));
    }

    @Test
    @DisplayName("读取命中：JSON 反序列化回 RecentImage，字段无损")
    @SuppressWarnings("unchecked")
    void shouldFindAndDeserializeCachedImage() {
        RedissonClient client = mock(RedissonClient.class);
        RBucket<String> bucket = mock(RBucket.class);
        doReturn(bucket).when(client).getBucket(RedisRecentImageStore.key(OPENID));
        when(bucket.get()).thenReturn(JsonUtils.toJson(sample()));

        RedisRecentImageStore store = new RedisRecentImageStore(client);

        RecentImage image = store.find(OPENID).orElseThrow();
        assertThat(image.description()).isEqualTo("一只橘色的成年短毛猫");
        assertThat(image.scene()).isEqualTo("pet");
        assertThat(image.confidence()).isEqualTo(0.9);
        assertThat(image.recognizedAt()).isEqualTo(Instant.parse("2026-09-28T12:00:00Z"));
    }

    @Test
    @DisplayName("无缓存 / 空值 → find 返回空")
    @SuppressWarnings("unchecked")
    void shouldReturnEmptyWhenAbsent() {
        RedissonClient client = mock(RedissonClient.class);
        RBucket<String> bucket = mock(RBucket.class);
        doReturn(bucket).when(client).getBucket(RedisRecentImageStore.key(OPENID));
        when(bucket.get()).thenReturn(null);

        assertThat(new RedisRecentImageStore(client).find(OPENID)).isEmpty();
    }

    @Test
    @DisplayName("Redis 不可用（客户端为空 / 运行时异常）→ save false、find 空，主链路 fail-open")
    @SuppressWarnings("unchecked")
    void shouldDegradeWhenRedisUnavailable() {
        assertThat(new RedisRecentImageStore(null).save(OPENID, sample())).isFalse();
        assertThat(new RedisRecentImageStore(null).find(OPENID)).isEmpty();

        RedissonClient broken = mock(RedissonClient.class);
        doThrow(new IllegalStateException("redis down")).when(broken).getBucket(anyString());
        RedisRecentImageStore store = new RedisRecentImageStore(broken);

        assertThat(store.save(OPENID, sample())).isFalse();
        assertThat(store.find(OPENID)).isEmpty();
    }
}
