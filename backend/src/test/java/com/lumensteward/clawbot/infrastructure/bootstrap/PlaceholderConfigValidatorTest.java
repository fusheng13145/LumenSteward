package com.lumensteward.clawbot.infrastructure.bootstrap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 占位符校验器单元测试（SUP-06 / AC-D2 / AC-F3）。
 *
 * <p>覆盖：prod 下 Fail-Fast 且列明缺失项；local 下仅告警不阻断；关键项齐备时无问题项。
 */
class PlaceholderConfigValidatorTest {

    private static final String[] NO_ARGS = new String[0];

    @Test
    @DisplayName("prod：占位符/缺失即 Fail-Fast，并在异常信息中列明问题项")
    void shouldFailFastOnPlaceholderInProd() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        environment.setProperty("wx.token", "your_wechat_token");
        environment.setProperty("llm.api-key", "change_me");
        environment.setProperty("spring.datasource.password", "Str0ngRealPassword");
        PlaceholderConfigValidator validator = new PlaceholderConfigValidator(environment);

        List<String> problems = validator.findPlaceholderProblems();
        assertThat(problems)
                .contains("wx.token=占位符", "llm.api-key=占位符", "security.jwt-secret=缺失");
        assertThat(validator.isFailFast()).isTrue();

        assertThatThrownBy(() -> validator.run(new DefaultApplicationArguments(NO_ARGS)))
                .as("prod 下应拒绝启动")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wx.token")
                .hasMessageContaining("SUP-06");
    }

    @Test
    @DisplayName("local：占位符/缺失仅告警，不阻断启动")
    void shouldOnlyWarnInLocal() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local");
        PlaceholderConfigValidator validator = new PlaceholderConfigValidator(environment);

        assertThat(validator.isFailFast()).isFalse();
        assertThat(validator.findPlaceholderProblems()).isNotEmpty();
        assertThatCode(() -> validator.run(new DefaultApplicationArguments(NO_ARGS)))
                .as("local 下不应抛异常")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("关键项全部就绪：无问题项")
    void shouldReportNoProblemsWhenAllConfigured() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        for (String key : PlaceholderConfigValidator.CRITICAL_KEYS) {
            environment.setProperty(key, "real-value-for-" + key);
        }
        PlaceholderConfigValidator validator = new PlaceholderConfigValidator(environment);

        assertThat(validator.findPlaceholderProblems()).isEmpty();
        assertThatCode(() -> validator.run(new DefaultApplicationArguments(NO_ARGS)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("显式开关 clawbot.startup.placeholder-fail-fast=true 亦触发 Fail-Fast（非 prod）")
    void shouldFailFastWhenExplicitSwitchEnabled() {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("clawbot.startup.placeholder-fail-fast", "true");
        PlaceholderConfigValidator validator = new PlaceholderConfigValidator(environment);

        assertThat(validator.isFailFast()).isTrue();
        assertThatThrownBy(() -> validator.run(new DefaultApplicationArguments(NO_ARGS)))
                .isInstanceOf(IllegalStateException.class);
    }
}
