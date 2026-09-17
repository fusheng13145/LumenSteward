package com.lumensteward.clawbot.infrastructure.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Redisson 客户端配置（G-16 配置类收敛 / 分布式锁载体）。
 *
 * <p>显式构建 {@link RedissonClient}，**仅在口令非空白时才下发 AUTH**：
 * Redisson 官方 starter 在 {@code spring.data.redis.password} 为空串时仍会发送
 * {@code AUTH ""}，对**无口令的本地/默认 Redis** 会被服务端以
 * {@code ERR Client sent AUTH, but no password is set} 拒绝，导致应用启动失败
 * （而 {@code .env.example} 与 {@code docker-compose} 的默认 Redis 均为无口令）。
 * 因此此处以「空白即视为无口令」的方式对齐本地默认契约。
 *
 * <p>该 Bean 取代 starter 的 {@code RedissonAutoConfigurationV2} 所提供的客户端
 * （后者已在 {@code application.yml} 的 {@code spring.autoconfigure.exclude} 中排除）；
 * Spring Data Redis 的连接工厂由 Lettuce 自动装配提供，互不影响。
 */
@Configuration
public class RedissonConfig {

    /**
     * 构建单机模式 Redisson 客户端。
     *
     * @param host     主机（{@code spring.data.redis.host}）
     * @param port     端口（{@code spring.data.redis.port}）
     * @param password 口令（{@code spring.data.redis.password}，可为空）
     * @param database 库序号（{@code spring.data.redis.database}）
     * @return RedissonClient
     */
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(
            @Value("${spring.data.redis.host:localhost}") String host,
            @Value("${spring.data.redis.port:6379}") int port,
            @Value("${spring.data.redis.password:}") String password,
            @Value("${spring.data.redis.database:0}") int database) {
        Config config = new Config();
        SingleServerConfig server = config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setDatabase(database)
                .setConnectionMinimumIdleSize(2)
                .setConnectionPoolSize(16);
        // 关键：仅在口令非空白时才设置，避免对无口令 Redis 误发 AUTH
        if (StringUtils.hasText(password)) {
            server.setPassword(password);
        }
        return Redisson.create(config);
    }
}
