package com.lumensteward.clawbot.verification;

import com.lumensteward.clawbot.application.orchestrator.model.ToolCallRecord;
import com.lumensteward.clawbot.application.safety.ActionClaimExtractor;
import com.lumensteward.clawbot.application.safety.ConsistencyReason;
import com.lumensteward.clawbot.application.safety.ConsistencyVerdict;
import com.lumensteward.clawbot.application.safety.RuleBasedConsistencyChecker;
import com.lumensteward.clawbot.common.enums.ToolStatus;
import com.lumensteward.clawbot.common.util.JsonUtils;
import com.lumensteward.clawbot.infrastructure.config.properties.SafetyProperties;
import com.lumensteward.clawbot.support.ToolRegistries;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 独立验证：执行一致性校验（SRS 9.4.5 / BR-04 / AC-B13 / AC-B14）。
 *
 * <p>核心差异化能力：模型"声称已执行但无成功工具记录"必须整条拦截。QA 独立构造用例，
 * 不复用工程师测试类。
 */
class AntiHallucinationVerificationTest {

    private final RuleBasedConsistencyChecker checker =
            new RuleBasedConsistencyChecker(new ActionClaimExtractor(ToolRegistries.productionTools()),
                    new SafetyProperties("classpath:none/x.txt", true, false));

    private static ToolCallRecord record(String toolName, ToolStatus status, String resultJson) {
        return new ToolCallRecord(1L, "trace-qa", "openid-qa", 1L, toolName, 1, 0, status,
                null, null, JsonUtils.readTree("{}"), JsonUtils.readTree(resultJson), 5L);
    }

    @Test
    @DisplayName("AC-B13：声称『已查询到物流轨迹』但本次无任何成功工具记录 → 必须拦截")
    void unsupportedActionClaimBlocked() {
        String reply = "已为你查询到物流：您的包裹预计明天送达。";
        ConsistencyVerdict verdict = checker.check(reply, List.of());

        assertThat(verdict.passed()).as("无成功工具记录时必须拦截").isFalse();
        assertThat(verdict.reason()).isEqualTo(ConsistencyReason.CLAIM_UNSUPPORTED);
    }

    @Test
    @DisplayName("AC-B13：仅有无关成功工具（宠物档案）也不能支撑物流声明 → 仍拦截")
    void unsupportedClaimWithIrrelevantSuccessStillBlocked() {
        String reply = "已为你查询到物流：您的包裹预计明天送达。";
        List<ToolCallRecord> executed = List.of(
                record("manage_pet_profile", ToolStatus.SUCCESS, "{\"pet_name\":\"咪咪\"}"));

        ConsistencyVerdict verdict = checker.check(reply, executed);

        assertThat(verdict.passed()).isFalse();
        assertThat(verdict.reason()).isEqualTo(ConsistencyReason.CLAIM_UNSUPPORTED);
    }

    @Test
    @DisplayName("AC-B13：工具调用存在但为 FAILED/TIMEOUT（非 SUCCESS）→ 视为无支撑，拦截")
    void failedToolDoesNotSupportClaim() {
        String reply = "已为你查询到物流：您的包裹预计明天送达。";
        List<ToolCallRecord> executed = List.of(
                record("query_express", ToolStatus.FAILED, null),
                record("query_express", ToolStatus.TIMEOUT, null));

        ConsistencyVerdict verdict = checker.check(reply, executed);
        assertThat(verdict.passed()).isFalse();
    }

    @Test
    @DisplayName("AC-B14：数值有工具结果支撑 → 放行")
    void traceableNumericPasses() {
        String reply = "已查询到豆豆的档案，生日是 2020-05-01。";
        List<ToolCallRecord> executed = List.of(
                record("manage_pet_profile", ToolStatus.SUCCESS, "{\"birthday\":\"2020-05-01\"}"));

        ConsistencyVerdict verdict = checker.check(reply, executed);

        assertThat(verdict.passed()).as("数值可回溯必须放行").isTrue();
    }

    @Test
    @DisplayName("数值不可回溯（工具成功但结果不含该数值）→ 拦截")
    void nonTraceableNumericBlocked() {
        String reply = "已查询到物流轨迹，签收日期 2099-01-01。";
        List<ToolCallRecord> executed = List.of(
                record("query_express", ToolStatus.SUCCESS, "{\"status\":\"in_transit\"}"));

        ConsistencyVerdict verdict = checker.check(reply, executed);

        assertThat(verdict.passed()).isFalse();
        assertThat(verdict.reason()).isEqualTo(ConsistencyReason.VALUE_NOT_TRACEABLE);
    }

    @Test
    @DisplayName("边界：纯闲聊（无完成态动作声明）不做工具校验 → 放行")
    void noActionClaimPasses() {
        ConsistencyVerdict verdict = checker.check("你好呀，我在呢，有什么可以帮你？", List.of());
        assertThat(verdict.passed()).isTrue();
    }

    @Test
    @DisplayName("AC-B13 复现：连续 20 次构造执行性幻觉，对外泄漏 = 0")
    void twentyHallucinationsAllBlocked() {
        String reply = "已为你查询到物流：您的包裹预计明天送达。";
        int leaked = 0;
        for (int i = 0; i < 20; i++) {
            if (checker.check(reply, List.of()).passed()) {
                leaked++;
            }
        }
        assertThat(leaked).as("对外泄漏必须为 0").isZero();
    }
}
