package com.lumensteward.clawbot.application.validation;

import com.lumensteward.clawbot.application.config.ConfigKeys;
import com.lumensteward.clawbot.application.config.DynamicConfigService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 单条消息长度守卫单测（FR-20 ④）。
 */
class MessageLengthGuardTest {

    @Test
    @DisplayName("null 输入 → 不截断，长度记为 0")
    void nullInput() {
        MessageLengthGuard guard = new MessageLengthGuard(null);
        MessageLengthGuard.GuardResult r = guard.guard(null);
        assertThat(r.truncated()).isFalse();
        assertThat(r.originalLength()).isZero();
    }

    @Test
    @DisplayName("未超限 → 原样返回")
    void withinLimit() {
        MessageLengthGuard guard = new MessageLengthGuard(null);
        String text = "hello";
        MessageLengthGuard.GuardResult r = guard.guard(text);
        assertThat(r.truncated()).isFalse();
        assertThat(r.text()).isEqualTo(text);
        assertThat(r.originalLength()).isEqualTo(5);
    }

    @Test
    @DisplayName("超限 → 截断并附说明，originalLength 保留原长")
    void overLimit() {
        MessageLengthGuard guard = new MessageLengthGuard(null);
        String text = "x".repeat(2500);
        MessageLengthGuard.GuardResult r = guard.guard(text);
        assertThat(r.truncated()).isTrue();
        assertThat(r.originalLength()).isEqualTo(2500);
        assertThat(r.text()).contains("已截断至前 2000 字符");
    }

    @Test
    @DisplayName("上限经动态配置运行时调整")
    void configurableViaDynamicConfig() {
        DynamicConfigService cfg = mock(DynamicConfigService.class);
        when(cfg.getInt(ConfigKeys.RATE_LIMIT_MAX_MESSAGE_LENGTH, 2000)).thenReturn(10);
        MessageLengthGuard guard = new MessageLengthGuard(cfg);
        String text = "abcdefghijKLMNOP";
        MessageLengthGuard.GuardResult r = guard.guard(text);
        assertThat(r.truncated()).isTrue();
        assertThat(r.text()).contains("已截断至前 10 字符");
    }
}
