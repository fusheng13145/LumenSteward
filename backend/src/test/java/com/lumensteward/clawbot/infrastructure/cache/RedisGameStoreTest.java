package com.lumensteward.clawbot.infrastructure.cache;

import com.lumensteward.clawbot.common.util.JsonUtils;
import com.lumensteward.clawbot.domain.game.GameSession;
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
 * 桌游会话 Redis 存储测试（迭代 4 W19；键名 {@code game:{openid}}，TTL 24h 与上下文窗口同口径）。
 *
 * <p>以 Mockito 桩替代 Redisson，验证键名、写入 TTL、JSON 往返与「Redis 不可用即视为无局」的
 * fail-open 降级语义（对齐 RedisRecentImageStoreTest）。
 */
class RedisGameStoreTest {

    private static final String OPENID = "openid-game-key";

    private static GameSession sample() {
        return GameSession.forTurtleSoup("ts001", Instant.parse("2026-11-05T12:00:00Z"));
    }

    @Test
    @DisplayName("键名为 game:{openid}，写入携带 24h TTL（与上下文窗口同口径）")
    @SuppressWarnings("unchecked")
    void shouldSaveUnderGameKeyWithContextWindowTtl() {
        RedissonClient client = mock(RedissonClient.class);
        RBucket<String> bucket = mock(RBucket.class);
        doReturn(bucket).when(client).getBucket(RedisGameStore.key(OPENID));

        RedisGameStore store = new RedisGameStore(client);

        assertThat(RedisGameStore.key(OPENID)).isEqualTo("game:openid-game-key");
        assertThat(RedisGameStore.TTL).isEqualTo(Duration.ofHours(24));

        boolean saved = store.save(OPENID, sample());

        assertThat(saved).isTrue();
        verify(client).getBucket("game:openid-game-key");
        verify(bucket).set(anyString(), eq(RedisGameStore.TTL));
    }

    @Test
    @DisplayName("读取命中：JSON 反序列化回 GameSession，玩法与谜题编号无损")
    @SuppressWarnings("unchecked")
    void shouldFindAndDeserializeSession() {
        RedissonClient client = mock(RedissonClient.class);
        RBucket<String> bucket = mock(RBucket.class);
        doReturn(bucket).when(client).getBucket(RedisGameStore.key(OPENID));
        when(bucket.get()).thenReturn(JsonUtils.toJson(sample()));

        RedisGameStore store = new RedisGameStore(client);

        GameSession session = store.find(OPENID).orElseThrow();
        assertThat(session.game()).isEqualTo(com.lumensteward.clawbot.domain.game.GameType.TURTLE_SOUP);
        assertThat(session.puzzleId()).isEqualTo("ts001");
        assertThat(session.hintsUsed()).isZero();
        assertThat(session.startedAt()).isEqualTo(Instant.parse("2026-11-05T12:00:00Z"));
    }

    @Test
    @DisplayName("读取落空：无局返回 empty；delete 返回是否存在")
    @SuppressWarnings("unchecked")
    void shouldReturnEmptyWhenAbsentAndDeleteReportsExistence() {
        RedissonClient client = mock(RedissonClient.class);
        RBucket<String> bucket = mock(RBucket.class);
        doReturn(bucket).when(client).getBucket(RedisGameStore.key(OPENID));
        when(bucket.get()).thenReturn(null);
        when(bucket.delete()).thenReturn(true);

        RedisGameStore store = new RedisGameStore(client);

        assertThat(store.find(OPENID)).isEmpty();
        assertThat(store.delete(OPENID)).isTrue();
    }

    @Test
    @DisplayName("fail-open：客户端为 null 或 Redis 异常时按无局处理，不抛出")
    @SuppressWarnings("unchecked")
    void shouldDegradeGracefullyWhenRedisUnavailable() {
        assertThat(new RedisGameStore(null).save(OPENID, sample())).isFalse();
        assertThat(new RedisGameStore(null).find(OPENID)).isEmpty();
        assertThat(new RedisGameStore(null).delete(OPENID)).isFalse();

        RedissonClient broken = mock(RedissonClient.class);
        doThrow(new IllegalStateException("redis down")).when(broken).getBucket(anyString());

        RedisGameStore store = new RedisGameStore(broken);
        assertThat(store.save(OPENID, sample())).isFalse();
        assertThat(store.find(OPENID)).isEmpty();
        assertThat(store.delete(OPENID)).isFalse();
    }
}
