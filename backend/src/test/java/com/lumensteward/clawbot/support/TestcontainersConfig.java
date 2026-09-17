package com.lumensteward.clawbot.support;

import org.junit.jupiter.api.Tag;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;

/**
 * 集成测试基座（NFR-MA-04，架构 7.3）。
 *
 * <p>提供 MySQL 8 与 Redis 7 容器，并（对 Spring 上下文用例）统一注入数据源 / Redis / Flyway 动态属性，
 * 使集成用例<b>自包含</b>——不依赖开发者本机已运行 MySQL / Redis。所有集成用例均以
 * {@link Tag @Tag("integration")} 标注，并由 {@code maven-surefire-plugin} 的 {@code excludedGroups}
 * 显式排除——本地无 Docker 时不静默通过，而是被排除；CI / 本机核对时以 {@code -Dgroups=integration}
 * 运行（含 AC-C3 / AC-C6 迁移约束用例、D7 真实 MySQL 实证用例）。
 *
 * <p><b>D7 增强：</b>新增 {@link #REDIS} 容器与 {@link #registerProps(DynamicPropertyRegistry)}。
 * 后者标注 {@link DynamicPropertySource} 且位于基类——Spring 会自动应用于所有子测试类，
 * 故子类无需再各自声明（此前 {@code MainChainE2ETest}/{@code MainChainRowEvidenceTest} 依赖
 * 外部 {@code localhost:6379} Redis，是非自包含的隐性前置）。
 */
@Tag("integration")
@Testcontainers
public abstract class TestcontainersConfig {

    /** MySQL 镜像（与 docker-compose 保持一致）。 */
    private static final String MYSQL_IMAGE = "mysql:8.4";

    /** Redis 镜像（与应用本地/CI 契约一致）。 */
    private static final String REDIS_IMAGE = "redis:7-alpine";

    /** 共享 MySQL 容器。 */
    @Container
    protected static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse(MYSQL_IMAGE))
            .withDatabaseName("clawbot_test")
            .withUsername("clawbot")
            .withPassword("clawbot_test");

    /** 共享 Redis 容器（供 Redisson / StringRedisTemplate 使用，免依赖外部 Redis）。 */
    @Container
    protected static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE))
            .withExposedPorts(6379);

    /**
     * 统一注入 Spring 上下文所需的动态属性（数据源 + Redis + Flyway）。
     *
     * <p>由基类声明，Spring 自动应用于全部子测试类；{@code DataSource}-only 用例（如
     * {@code MySqlMigrationTest}）不使用 Spring 上下文，此方法对其无副作用。
     *
     * @param registry 动态属性注册表
     */
    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    /**
     * 构建指向容器的数据源。
     *
     * <p>使用 {@link DriverManagerDataSource}（按类名反射加载驱动），避免测试代码直接编译依赖
     * {@code runtime} 作用域的 MySQL 驱动。
     *
     * @return 指向测试容器的数据源
     */
    protected static DataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl(MYSQL.getJdbcUrl());
        dataSource.setUsername(MYSQL.getUsername());
        dataSource.setPassword(MYSQL.getPassword());
        return dataSource;
    }
}
