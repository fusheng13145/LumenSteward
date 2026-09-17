package com.lumensteward.clawbot.infrastructure.persistence;

import com.lumensteward.clawbot.infrastructure.persistence.entity.PetProfileEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 迁移脚本与实体的<b>静态断言</b>测试（G-01~G-07）。
 *
 * <p>在不依赖数据库的前提下，对 DDL 文本与实体类做结构性断言，把可自动检查的规范变为测试：
 * 域分文件不重复定义、引擎/字符集、字段 COMMENT、索引命名前缀、生成列唯一约束方案、
 * 以及「实体不得声明生成列 live_marker」。
 */
class MigrationScriptStaticTest {

    private static final List<String> BASELINE_FILES = List.of(
            "V1.0.0__baseline_sys.sql",
            "V1.0.1__baseline_wx.sql",
            "V1.0.2__baseline_biz.sql");

    private static final List<String> ALL_TABLES = List.of(
            "sys_admin_user", "sys_config", "log_audit",
            "wx_user", "wx_session", "wx_message",
            "biz_pet_profile", "log_tool_call");

    private static final Pattern INDEX_PATTERN =
            Pattern.compile("(?m)^\\s*(?:UNIQUE\\s+KEY|KEY)\\s+(\\w+)");

    @Test
    @DisplayName("G-06：8 张表齐全，且每张表只在唯一一个基线脚本中定义")
    void shouldDefineEachTableExactlyOnce() throws IOException {
        for (String table : ALL_TABLES) {
            Pattern createPattern = Pattern.compile(
                    "(?i)CREATE\\s+TABLE\\s+" + Pattern.quote(table) + "\\b");
            int definitions = 0;
            for (String file : BASELINE_FILES) {
                definitions += countMatches(read(file), createPattern);
            }
            assertThat(definitions)
                    .as("表 %s 应恰好定义一次（跨文件重复会造成 schema 漂移）", table)
                    .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("G-05：统一 ENGINE=InnoDB 与 utf8mb4 字符集")
    void shouldUseInnoDbAndUtf8mb4() throws IOException {
        for (String file : BASELINE_FILES) {
            String content = read(file);
            assertThat(content).as("%s 应含 ENGINE=InnoDB", file).contains("ENGINE=InnoDB");
            assertThat(content).as("%s 应含 utf8mb4", file).contains("utf8mb4");
            assertThat(content).as("%s 应含 utf8mb4_unicode_ci", file).contains("utf8mb4_unicode_ci");
        }
    }

    @Test
    @DisplayName("G-02：索引名统一 uk_ / idx_ 前缀，复合索引为 idx_<列1>_<列2>")
    void shouldNameIndexesWithPrefix() throws IOException {
        for (String file : BASELINE_FILES) {
            Matcher matcher = INDEX_PATTERN.matcher(read(file));
            int count = 0;
            while (matcher.find()) {
                count++;
                String indexName = matcher.group(1);
                assertThat(indexName)
                        .as("%s 中索引 %s 应使用 uk_ / idx_ 前缀", file, indexName)
                        .matches("^(uk_|idx_).+");
            }
            assertThat(count).as("%s 应至少定义一个索引", file).isPositive();
        }
    }

    @Test
    @DisplayName("G-03 / 7.6.2：审计字段与 deleted_at 语义（NULL 表示未删除）齐备")
    void shouldDeclareAuditColumns() throws IOException {
        String combined = readBaselineCombined();
        assertThat(combined).contains("created_at");
        assertThat(combined).contains("deleted_at");
        assertThat(combined).contains("软删除时间:NULL 表示未删除");
    }

    @Test
    @DisplayName("架构 3.3：biz_pet_profile 使用生成列 live_marker + 复合唯一约束")
    void shouldUseGeneratedColumnUniqueConstraint() throws IOException {
        String biz = read("V1.0.2__baseline_biz.sql");
        assertThat(biz).contains("GENERATED ALWAYS AS (IF(deleted_at IS NULL, 1, NULL)) STORED");
        assertThat(biz).contains("UNIQUE KEY uk_openid_pet_name_live_marker (openid, pet_name, live_marker)");
        // 明确否决 PRD/SRS 7.2 的 UNIQUE(openid, pet_name, deleted_at)
        assertThat(biz).doesNotContain("UNIQUE KEY uk_openid_pet_name_deleted_at");
    }

    @Test
    @DisplayName("架构 3.3：PetProfileEntity 不得声明生成列 live_marker")
    void entityShouldNotDeclareLiveMarker() {
        List<String> fieldNames = Arrays.stream(PetProfileEntity.class.getDeclaredFields())
                .map(Field::getName)
                .toList();
        assertThat(fieldNames)
                .as("实体声明生成列会导致 INSERT/UPDATE 报错")
                .doesNotContain("liveMarker");
        assertThat(fieldNames).contains("deletedAt");
    }

    @Test
    @DisplayName("种子数据不含密钥明文（BR-20）")
    void seedShouldNotContainPlaintextSecrets() throws IOException {
        String seed = read("V1.0.3__seed_sys_config.sql");
        assertThat(seed).contains("wx.token");
        assertThat(seed).as("wx.token 应以空值占位、is_encrypted=1").contains("'SECRET'");
        assertThat(seed).doesNotContain("sk-").doesNotContain("password=");
        assertThat(read("V1.0.4__seed_admin.sql")).contains("__ENV_INJECTED__");
    }

    private String readBaselineCombined() throws IOException {
        StringBuilder builder = new StringBuilder();
        for (String file : BASELINE_FILES) {
            builder.append(read(file));
        }
        return builder.toString();
    }

    private static String read(String fileName) throws IOException {
        Resource resource = new ClassPathResource("db/migration/" + fileName);
        assertThat(resource.exists()).as("迁移脚本应存在：%s", fileName).isTrue();
        return resource.getContentAsString(StandardCharsets.UTF_8);
    }

    private static int countMatches(String content, Pattern pattern) {
        Matcher matcher = pattern.matcher(content);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }
}
