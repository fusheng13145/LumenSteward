package com.lumensteward.clawbot.safety;

import com.fasterxml.jackson.databind.JsonNode;
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
 * 执行一致性校验 / 反幻觉测试（AC-B13/B14，SRS 9.4.5，TC-H）。
 *
 * <p>覆盖：纯闲聊 PASSED；声称执行但无成功记录 DETECTED；数值不可回溯 DETECTED；
 * 工具成功且数值可回溯 PASSED。
 */
class ConsistencyCheckHallucinationTest {

    private final RuleBasedConsistencyChecker checker = new RuleBasedConsistencyChecker(
            new ActionClaimExtractor(ToolRegistries.productionTools()),
            new SafetyProperties("classpath:safety/wordlist.txt", true, false));

    @Test
    @DisplayName("④ 纯闲聊无动作声明 → PASSED（不做工具校验）")
    void chitchatShouldPass() {
        ConsistencyVerdict verdict = checker.check("今天天气不错呀，你最近好吗？", List.of());
        assertThat(verdict.passed()).isTrue();
        assertThat(verdict.reason()).isEqualTo(ConsistencyReason.PASSED);
    }

    @Test
    @DisplayName("④ 声称已查快递但无调用记录 → DETECTED（整条拦截）")
    void claimWithoutRecordShouldBeDetected() {
        ConsistencyVerdict verdict = checker.check("已为你查询到物流：您的包裹预计明天送达。", List.of());
        assertThat(verdict.passed()).isFalse();
        assertThat(verdict.reason()).isEqualTo(ConsistencyReason.CLAIM_UNSUPPORTED);
    }

    @Test
    @DisplayName("④ 声称成功但工具未成功（无成功记录）→ DETECTED")
    void claimSuccessButToolFailedShouldBeDetected() {
        ToolCallRecord failed = record("manage_pet_profile", ToolStatus.FAILED,
                JsonUtils.readTree("{\"message\":\"write failed\"}"));
        ConsistencyVerdict verdict = checker.check("已为你记录好宠物档案啦。", List.of(failed));
        assertThat(verdict.passed()).isFalse();
        assertThat(verdict.reason()).isEqualTo(ConsistencyReason.CLAIM_UNSUPPORTED);
    }

    @Test
    @DisplayName("④ 工具成功但数值不可回溯 → DETECTED（VALUE_NOT_TRACEABLE）")
    void untraceableNumericShouldBeDetected() {
        ToolCallRecord success = record("manage_pet_profile", ToolStatus.SUCCESS,
                JsonUtils.readTree("{\"pet_name\":\"咪咪\",\"pet_type\":\"猫\"}"));
        ConsistencyVerdict verdict = checker.check("已为你记录好，体重 12 公斤。", List.of(success));
        assertThat(verdict.passed()).isFalse();
        assertThat(verdict.reason()).isEqualTo(ConsistencyReason.VALUE_NOT_TRACEABLE);
    }

    @Test
    @DisplayName("④ 工具成功且数值可回溯 → PASSED（BR-04 数值可回溯）")
    void traceableNumericShouldPass() {
        ToolCallRecord success = record("manage_pet_profile", ToolStatus.SUCCESS,
                JsonUtils.readTree("{\"pet_name\":\"咪咪\",\"weight_kg\":12}"));
        ConsistencyVerdict verdict = checker.check("已为你记录好，体重 12 公斤。", List.of(success));
        assertThat(verdict.passed()).isTrue();
    }

    @Test
    @DisplayName("④ 声称记录档案且有成功记录 → PASSED")
    void supportedClaimShouldPass() {
        ToolCallRecord success = record("manage_pet_profile", ToolStatus.SUCCESS,
                JsonUtils.readTree("{\"pet_name\":\"咪咪\"}"));
        ConsistencyVerdict verdict = checker.check("好嘞，咪咪的档案我已经记下啦。", List.of(success));
        assertThat(verdict.passed()).isTrue();
    }

    private static ToolCallRecord record(String toolName, ToolStatus status, JsonNode result) {
        return new ToolCallRecord(1L, "trace", "openid", 1L, toolName, 0, 0, status,
                status == ToolStatus.SUCCESS ? null : "TOOL_FAILED",
                status == ToolStatus.SUCCESS ? null : "TOOL_FAILED",
                JsonUtils.readTree("{}"), result, 5L);
    }
}
