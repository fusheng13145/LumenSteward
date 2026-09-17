package com.lumensteward.clawbot.verification;

import com.lumensteward.clawbot.infrastructure.cache.RedisDedupService;
import com.lumensteward.clawbot.infrastructure.cache.RedisRateLimitService;
import com.lumensteward.clawbot.infrastructure.config.properties.WechatProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.net.InetSocketAddress;
import java.net.Socket;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 独立验证（实跑）：Redis 幂等去重与限流（SRS 9.4.6 / AC-A3 / BR-29）。
 *
 * <p>本机 Redis 7 可用（无口令），故以<b>真实 Redis</b> 实跑，取得端到端实证。
 * 标注 {@code @Tag("redis-live")}；Redis 不可达时由 {@code assumeTrue} 自动跳过（保证可移植），
 * 也可用 {@code -Dgroups=redis-live} 单独触发。
 */
@Tag("redis-live")
class RedisLiveCacheVerificationTest {

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate template;

    @BeforeAll
    static void setUp() {
        Assumptions.assumeTrue(canConnect("127.0.0.1", 6379),
                "Redis 127.0.0.1:6379 不可达，跳过实跑");
        factory = new LettuceConnectionFactory("127.0.0.1", 6379);
        factory.afterPropertiesSet();
        template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
    }

    @AfterAll
    static void tearDown() {
        if (factory != null) {
            factory.destroy();
        }
    }

    private static boolean canConnect(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 1500);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    @DisplayName("AC-A3：同一 MsgId 连续 10 次仅第 1 次被受理（10→1）")
    void dedupTenToOne() {
        RedisDedupService dedup = new RedisDedupService(template,
                new WechatProperties("", "", "", "", true, 300, 300));
        String msgId = "QA-DEDUP-" + System.nanoTime();
        template.delete(RedisDedupService.KEY_PREFIX + msgId);

        int accepted = 0;
        for (int i = 0; i < 10; i++) {
            if (dedup.markIfAbsent(msgId)) {
                accepted++;
            }
        }

        assertThat(accepted).as("10 次同 MsgId 推送应仅受理 1 次").isEqualTo(1);
        template.delete(RedisDedupService.KEY_PREFIX + msgId);
    }

    @Test
    @DisplayName("BR-29：单用户每分钟窗口第 61 次超限（前 60 次允许）")
    void rateLimitBlocksAfterSixty() {
        RedisRateLimitService rateLimit = new RedisRateLimitService(template);
        String openid = "QA-RL-" + System.nanoTime();
        template.delete(RedisRateLimitService.USER_KEY_PREFIX + openid);

        int allowed = 0;
        for (int i = 0; i < 61; i++) {
            if (rateLimit.tryAcquire(openid, null)) {
                allowed++;
            }
        }

        assertThat(allowed).as("前 60 次允许、第 61 次拒绝").isEqualTo(60);
        template.delete(RedisRateLimitService.USER_KEY_PREFIX + openid);
    }

    @Test
    @DisplayName("BR-29：Redis 不可用（阈值不可达）时应 Fail-Open —— 由实现保证，此处仅确认正常路径不抛异常")
    void normalPathDoesNotThrow() {
        RedisRateLimitService rateLimit = new RedisRateLimitService(template);
        String openid = "QA-RL-OK-" + System.nanoTime();
        template.delete(RedisRateLimitService.USER_KEY_PREFIX + openid);

        assertThat(rateLimit.tryAcquire(openid, "127.0.0.1")).isTrue();
        template.delete(RedisRateLimitService.USER_KEY_PREFIX + openid);
        template.delete(RedisRateLimitService.IP_KEY_PREFIX + "127.0.0.1");
    }
}
