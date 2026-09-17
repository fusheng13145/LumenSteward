package com.lumensteward.clawbot.safety;

import com.lumensteward.clawbot.application.safety.LocalWordlistSafetyService;
import com.lumensteward.clawbot.application.safety.SafetyVerdict;
import com.lumensteward.clawbot.infrastructure.config.properties.SafetyProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内容安全 Fail-Closed 测试（AC-B15，SRS 9.5，BR-12）。
 */
class ContentSafetyFailClosedTest {

    private final DefaultResourceLoader loader = new DefaultResourceLoader();

    @Test
    @DisplayName("⑤ 词库不可用且 fail-closed=true → 拒绝（Fail-Closed）")
    void unavailableShouldFailClosed() {
        LocalWordlistSafetyService service = new LocalWordlistSafetyService(
                new SafetyProperties("classpath:no-such-wordlist.txt", true, false),
                loader, "classpath:also-missing.txt");
        SafetyVerdict verdict = service.review("任意内容");
        assertThat(verdict.passed()).isFalse();
        assertThat(verdict.serviceUnavailable()).isTrue();
    }

    @Test
    @DisplayName("⑤ 词库不可用且 fail-closed=false → 放行（显式关闭才放行）")
    void unavailableWithFailOpenShouldPass() {
        LocalWordlistSafetyService service = new LocalWordlistSafetyService(
                new SafetyProperties("classpath:no-such-wordlist.txt", false, false),
                loader, "classpath:also-missing.txt");
        assertThat(service.review("任意内容").passed()).isTrue();
    }

    @Test
    @DisplayName("⑤ 命中敏感词 → 拒绝并返回命中词")
    void hitShouldReject() {
        LocalWordlistSafetyService service = new LocalWordlistSafetyService(
                new SafetyProperties("classpath:safety/wordlist.txt", true, false),
                loader, "classpath:safety/wordlist.txt");
        SafetyVerdict verdict = service.review("这里包含赌博相关字眼");
        assertThat(verdict.passed()).isFalse();
        assertThat(verdict.hitWord()).isEqualTo("赌博");
        assertThat(verdict.serviceUnavailable()).isFalse();
    }

    @Test
    @DisplayName("⑤ 正常文本 → 通过")
    void cleanTextShouldPass() {
        LocalWordlistSafetyService service = new LocalWordlistSafetyService(
                new SafetyProperties("classpath:safety/wordlist.txt", true, false),
                loader, "classpath:safety/wordlist.txt");
        assertThat(service.review("今天想给咪咪记录一下体重").passed()).isTrue();
    }
}
