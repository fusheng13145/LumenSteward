package com.lumensteward.clawbot.support;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 迁移与唯一约束集成测试（AC-F1 / AC-C3 / AC-C6，架构 3.3 / 7.3）。
 *
 * <p>在 Testcontainers MySQL 8 上执行 Flyway 迁移，验证：
 * <ul>
 *   <li>空库迁移后 8 张表全部建立（AC-F1）；</li>
 *   <li>活记录同名宠物被 {@code uk_openid_pet_name_live_marker} 拦截（AC-C3）；</li>
 *   <li>软删除后可重建同名宠物（AC-C6）。</li>
 * </ul>
 *
 * <p>标注 {@code @Tag("integration")}：由 surefire {@code excludedGroups} 排除（无 Docker 时）。
 */
@Tag("integration")
class MySqlMigrationTest extends TestcontainersConfig {

    private static final List<String> EXPECTED_TABLES = List.of(
            "wx_user", "wx_session", "wx_message", "biz_pet_profile",
            "log_tool_call", "sys_admin_user", "sys_config", "log_audit");

    @BeforeAll
    static void migrate() {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource())
                .locations("classpath:db/migration")
                .load();
        flyway.migrate();
    }

    @Test
    @DisplayName("AC-F1：空库迁移后 8 张表全部建立")
    void shouldCreateAllEightTables() throws SQLException {
        try (Connection connection = dataSource().getConnection()) {
            for (String table : EXPECTED_TABLES) {
                assertThat(tableExists(connection, table))
                        .as("表 %s 应存在", table)
                        .isTrue();
            }
        }
    }

    @Test
    @DisplayName("AC-C3：活记录同名宠物被唯一约束拦截")
    void shouldBlockDuplicateLivePetName() throws SQLException {
        try (Connection connection = dataSource().getConnection()) {
            insertPet(connection, "openid-ac-c3", "豆豆");
            assertThatThrownBy(() -> insertPet(connection, "openid-ac-c3", "豆豆"))
                    .as("同名活宠物应触发唯一约束冲突")
                    .isInstanceOf(SQLException.class);
        }
    }

    @Test
    @DisplayName("AC-C6：软删除后可重建同名宠物")
    void shouldAllowRecreateAfterSoftDelete() throws SQLException {
        try (Connection connection = dataSource().getConnection()) {
            String openid = "openid-ac-c6";
            String petName = "球球";
            insertPet(connection, openid, petName);
            softDeletePet(connection, openid, petName);

            // 删除后重建同名：live_marker 为 NULL，不参与唯一性比较，故应成功
            assertThatCode(() -> insertPet(connection, openid, petName))
                    .as("软删除后应可重建同名宠物")
                    .doesNotThrowAnyException();

            assertThat(countLive(connection, openid, petName))
                    .as("应有且仅有 1 条同名活记录")
                    .isEqualTo(1);
        }
    }

    private static void insertPet(Connection connection, String openid, String petName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO biz_pet_profile (openid, pet_name, pet_type) VALUES (?, ?, ?)")) {
            statement.setString(1, openid);
            statement.setString(2, petName);
            statement.setString(3, "猫");
            statement.executeUpdate();
        }
    }

    private static void softDeletePet(Connection connection, String openid, String petName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE biz_pet_profile SET deleted_at = NOW() WHERE openid = ? AND pet_name = ? AND deleted_at IS NULL")) {
            statement.setString(1, openid);
            statement.setString(2, petName);
            statement.executeUpdate();
        }
    }

    private static int countLive(Connection connection, String openid, String petName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM biz_pet_profile WHERE openid = ? AND pet_name = ? AND deleted_at IS NULL")) {
            statement.setString(1, openid);
            statement.setString(2, petName);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = ?")) {
            statement.setString(1, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1) > 0;
            }
        }
    }
}
