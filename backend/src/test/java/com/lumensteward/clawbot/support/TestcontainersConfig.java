package com.lumensteward.clawbot.support;

import org.junit.jupiter.api.Tag;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;

/**
 * 集成测试基座（NFR-MA-04，架构 7.3）。
 *
 * <p>提供 MySQL 8 容器与 {@link DataSource}。所有集成用例均以 {@link Tag @Tag("integration")} 标注，
 * 并由 {@code maven-surefire-plugin} 的 {@code excludedGroups} 显式排除——本地无 Docker 时不静默
 * 通过，而是被排除；CI 中另以 {@code -Dgroups=integration} 运行（含 AC-C3 / AC-C6 迁移约束用例）。
 *
 * <p>仅提供 MySQL 容器；Redis 集成测试待 T03 引入 Redis 依赖实现后再补充。
 */
@Tag("integration")
@Testcontainers
public abstract class TestcontainersConfig {

    /** MySQL 镜像（与 docker-compose 保持一致）。 */
    private static final String MYSQL_IMAGE = "mysql:8.4";

    /** 共享 MySQL 容器。 */
    @Container
    protected static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse(MYSQL_IMAGE))
            .withDatabaseName("clawbot_test")
            .withUsername("clawbot")
            .withPassword("clawbot_test");

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
