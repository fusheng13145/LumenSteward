package com.lumensteward.clawbot.infrastructure.bootstrap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 初始管理员口令注入器单元测试（Q7 / BR-20 / SUP-06）。
 *
 * <p>覆盖：环境变量存在时采用；prod 缺失即 Fail-Fast；local 缺失时生成一次性随机口令；
 * 口令经 BCrypt 编码（不落明文）。
 */
class AdminInitializerTest {

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Test
    @DisplayName("环境变量存在：直接采用该明文口令")
    void shouldUseEnvPasswordWhenPresent() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty(AdminInitializer.ENV_ADMIN_INIT_PASSWORD, "S3cret!AdminPass");
        AdminInitializer initializer = new AdminInitializer(passwordEncoder, environment);

        assertThat(initializer.resolveInitialPassword()).isEqualTo("S3cret!AdminPass");
        assertThat(initializer.encode("S3cret!AdminPass"))
                .as("应为 BCrypt 哈希")
                .startsWith("$2");
    }

    @Test
    @DisplayName("prod 且缺失环境变量：Fail-Fast")
    void shouldFailFastInProdWhenMissing() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        AdminInitializer initializer = new AdminInitializer(passwordEncoder, environment);

        assertThat(initializer.isFailFast()).isTrue();
        assertThatThrownBy(initializer::resolveInitialPassword)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(AdminInitializer.ENV_ADMIN_INIT_PASSWORD);
    }

    @Test
    @DisplayName("local 且缺失环境变量：生成一次性随机口令（16 位）")
    void shouldGenerateOneTimePasswordInLocal() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local");
        AdminInitializer initializer = new AdminInitializer(passwordEncoder, environment);

        String first = initializer.resolveInitialPassword();
        String second = initializer.resolveInitialPassword();

        assertThat(first).isNotBlank().hasSize(16);
        assertThat(second).isNotBlank().hasSize(16);
        assertThat(first).as("两次生成应不同（随机性）").isNotEqualTo(second);
    }

    @Test
    @DisplayName("占位哈希标记与迁移脚本一致")
    void placeholderHashShouldMatchSeedConvention() {
        assertThat(AdminInitializer.PLACEHOLDER_HASH).isEqualTo("__ENV_INJECTED__");
    }
}
