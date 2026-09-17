package com.lumensteward.clawbot.verification;

import com.lumensteward.clawbot.common.util.MaskUtils;
import com.lumensteward.clawbot.infrastructure.persistence.entity.SysConfigEntity;
import com.lumensteward.clawbot.infrastructure.persistence.entity.WxUserEntity;
import com.lumensteward.clawbot.interfaces.assembler.MaskingAssembler;
import com.lumensteward.clawbot.interfaces.dto.config.ConfigVO;
import com.lumensteward.clawbot.interfaces.dto.user.UserVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 独立验证：出参脱敏（SRS BR-21 / BR-15 / BR-30 / AC-E8）。
 *
 * <p>要求 openid 呈 {@code 前4 + **** + 后4}，且 API 视图中不得出现明文。
 */
class MaskingVerificationTest {

    private final MaskingAssembler assembler = new MaskingAssembler();

    @Test
    @DisplayName("AC-E8：MaskUtils.openid 前4+****+后4，且不含明文")
    void openidMaskingRule() {
        String raw = "oABCDEFGHIJKLMNOPQRSTUVWXy1"; // 27 字符
        String expected = raw.substring(0, 4) + "****" + raw.substring(raw.length() - 4);

        String masked = MaskUtils.openid(raw);

        assertThat(masked).isEqualTo(expected);
        assertThat(masked).startsWith("oABC****");
        assertThat(masked).doesNotContain(raw);
    }

    @Test
    @DisplayName("AC-E8：短标识整体打码，避免泄露")
    void shortOpenidFullyMasked() {
        assertThat(MaskUtils.openid("short")).isEqualTo("****");
        assertThat(MaskUtils.openid(null)).isNull();
        assertThat(MaskUtils.openid("   ")).isNull();
    }

    @Test
    @DisplayName("AC-E8：SECRET 仅保留尾 4 位")
    void secretMasking() {
        assertThat(MaskUtils.secret("sk-live-abcdef123456")).isEqualTo("****3456");
        assertThat(MaskUtils.secret("ab")).isEqualTo("****");
        assertThat(MaskUtils.secret(null)).isNull();
    }

    @Test
    @DisplayName("AC-E8：用户视图中的 openid 已脱敏，明文不出现")
    void userViewMasked() {
        String raw = "oABCDEFGHIJKLMNOPQRSTUVWXy1";
        WxUserEntity entity = new WxUserEntity();
        entity.setId(1L);
        entity.setOpenid(raw);
        entity.setNickname("测试用户");
        entity.setStatus(1);

        UserVO vo = assembler.toUserVO(entity);

        assertThat(vo.openid()).isNotEqualTo(raw);
        assertThat(vo.openid()).contains("****");
        assertThat(vo.openid()).doesNotContain(raw);
    }

    @Test
    @DisplayName("AC-E8：SECRET 类型配置值在视图中仅返回尾号")
    void secretConfigMasked() {
        SysConfigEntity entity = new SysConfigEntity();
        entity.setConfigKey("llm.apiKey");
        entity.setConfigValue("sk-secret-abcdef9999");
        entity.setValueType("SECRET");
        entity.setCategory("llm");

        ConfigVO vo = assembler.toConfigVO(entity);

        assertThat(vo.configValue()).isEqualTo("****9999");
        assertThat(vo.configValue()).doesNotContain("abcdef");
    }
}
