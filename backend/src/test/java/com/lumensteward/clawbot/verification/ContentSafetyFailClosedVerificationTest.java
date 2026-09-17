package com.lumensteward.clawbot.verification;

import com.lumensteward.clawbot.application.safety.LocalWordlistSafetyService;
import com.lumensteward.clawbot.application.safety.SafetyVerdict;
import com.lumensteward.clawbot.infrastructure.config.properties.SafetyProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 独立验证：内容安全 Fail-Closed（SRS FR-09 / BR-12 / AC-B15）。
 *
 * <p>关键：词库不可用时<b>不得放行</b>（Fail-Closed）。QA 通过构造"两条词库路径均不存在"
 * 的实例，独立复核该契约（不复用工程师测试类）。
 */
class ContentSafetyFailClosedVerificationTest {

    private static final String MISSING = "classpath:qa-none/wordlist.txt";

    @Test
    @DisplayName("AC-B15②：词库不可用 + Fail-Closed=true → 必须判为不通过（不放行）")
    void unavailableWordlistFailsClosed() {
        LocalWordlistSafetyService service = new LocalWordlistSafetyService(
                new SafetyProperties(MISSING, true, false), new DefaultResourceLoader(), MISSING);

        SafetyVerdict verdict = service.review("这是一段完全正常的内容");

        assertThat(verdict.passed()).as("Fail-Closed 下不得放行").isFalse();
        assertThat(verdict.serviceUnavailable()).isTrue();
    }

    @Test
    @DisplayName("词库不可用 + Fail-Closed=false（显式关闭）→ 放行（对照，确认开关生效）")
    void unavailableWordlistOpenWhenDisabled() {
        LocalWordlistSafetyService service = new LocalWordlistSafetyService(
                new SafetyProperties(MISSING, false, false), new DefaultResourceLoader(), MISSING);

        assertThat(service.review("正常内容").passed()).isTrue();
    }

    @Test
    @DisplayName("AC-B15①：词库可用时命中敏感词 → 拦截；正常文本 → 放行")
    void hitWordBlockedAndNormalPasses() {
        // 配置路径不存在 → 回落到真实内置词库 classpath:safety/wordlist.txt
        LocalWordlistSafetyService service = new LocalWordlistSafetyService(
                new SafetyProperties(MISSING, true, false), new DefaultResourceLoader(),
                "classpath:safety/wordlist.txt");

        assertThat(service.review("这里面有赌博的字样").passed()).as("命中敏感词必须拦截").isFalse();
        assertThat(service.review("我想给猫登记一下档案").passed()).as("正常文本必须放行").isTrue();
    }

    @Test
    @DisplayName("空文本放行（词库可用时）")
    void blankTextPasses() {
        LocalWordlistSafetyService service = new LocalWordlistSafetyService(
                new SafetyProperties(MISSING, true, false), new DefaultResourceLoader(),
                "classpath:safety/wordlist.txt");
        assertThat(service.review("").passed()).isTrue();
    }
}
