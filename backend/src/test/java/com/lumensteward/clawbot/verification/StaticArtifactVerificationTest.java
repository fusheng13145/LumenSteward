package com.lumensteward.clawbot.verification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 独立验证：数据库迁移与初始管理员引导的静态产物（SRS AC-F1 / BR-20）。
 *
 * <p>本机无 MySQL 凭据，无法执行真实迁移；此处以静态审查取最大实证，
 * 并明确：真实迁移执行属"无法验证"（见 QA 报告）。
 */
class StaticArtifactVerificationTest {

    private static String read(String classpath) {
        try {
            Resource resource = new ClassPathResource(classpath);
            try (InputStream in = resource.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取资源失败: " + classpath, e);
        }
    }

    private static String allMigrations() {
        return read("db/migration/V1.0.0__baseline_sys.sql")
                + "\n" + read("db/migration/V1.0.1__baseline_wx.sql")
                + "\n" + read("db/migration/V1.0.2__baseline_biz.sql");
    }

    @Test
    @DisplayName("AC-F1：8 张表全部定义（sys_admin_user/sys_config/log_audit/wx_user/wx_session/wx_message/biz_pet_profile/log_tool_call）")
    void eightTablesDefined() {
        String ddl = allMigrations();
        for (String table : new String[]{"sys_admin_user", "sys_config", "log_audit", "wx_user",
                "wx_session", "wx_message", "biz_pet_profile", "log_tool_call"}) {
            assertThat(ddl).as("表 %s 应存在", table).contains("CREATE TABLE " + table + " (");
        }
    }

    @Test
    @DisplayName("AC-C6：biz_pet_profile 采用生成列方案支撑『软删后可重建同名』")
    void petUniqueUsesGeneratedColumn() {
        String ddl = read("db/migration/V1.0.2__baseline_biz.sql");

        assertThat(ddl).contains("GENERATED ALWAYS AS");
        assertThat(ddl).contains("UNIQUE KEY uk_openid_pet_name_live_marker");
        assertThat(ddl).contains("live_marker");
    }

    @Test
    @DisplayName("AC-F1：表引擎 InnoDB、字符集 utf8mb4")
    void engineAndCharset() {
        String ddl = allMigrations();
        int tables = ddl.split("CREATE TABLE ", -1).length - 1;
        assertThat(tables).isEqualTo(8);
        assertThat(ddl.split("ENGINE=InnoDB", -1).length - 1)
                .as("每张表均须 InnoDB").isGreaterThanOrEqualTo(8);
        assertThat(ddl).contains("CHARSET=utf8mb4");
    }

    @Test
    @DisplayName("BR-20：初始管理员口令非硬编码（占位符 __ENV_INJECTED__，无 BCrypt 明文哈希）")
    void adminSeedHasNoHardcodedPassword() {
        String seed = read("db/migration/V1.0.4__seed_admin.sql");

        assertThat(seed).contains("__ENV_INJECTED__");
        assertThat(seed).doesNotContain("$2a$").doesNotContain("$2b$").doesNotContain("$2y$");
    }
}
