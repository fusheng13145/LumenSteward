package com.lumensteward.clawbot.assembler;

import com.lumensteward.clawbot.common.api.PageResult;
import com.lumensteward.clawbot.infrastructure.persistence.entity.SysConfigEntity;
import com.lumensteward.clawbot.infrastructure.persistence.entity.ToolCallLogEntity;
import com.lumensteward.clawbot.infrastructure.persistence.entity.WxUserEntity;
import com.lumensteward.clawbot.interfaces.assembler.MaskingAssembler;
import com.lumensteward.clawbot.interfaces.dto.config.ConfigVO;
import com.lumensteward.clawbot.interfaces.dto.toollog.ToolLogDetailVO;
import com.lumensteward.clawbot.interfaces.dto.user.UserVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 出参统一脱敏装配器单测（G-11 / BR-21 / BR-15，AC-E7 脱敏泄漏 = 0）。
 */
class MaskingAssemblerTest {

    private final MaskingAssembler assembler = new MaskingAssembler();

    @Test
    @DisplayName("openid 一律脱敏为 前4 + **** + 后4，且不含明文")
    void shouldMaskOpenid() {
        WxUserEntity user = new WxUserEntity();
        user.setId(1L);
        user.setOpenid("oABC1234567890XYZ");
        user.setNickname("小明");

        UserVO vo = assembler.toUserVO(user);

        assertThat(vo.openid()).isEqualTo("oABC****0XYZ");
        assertThat(vo.openid()).doesNotContain("1234567890");
        assertThat(vo.openid()).hasSize(12);
    }

    @Test
    @DisplayName("SECRET 类型配置仅返回尾 4 位，杜绝密钥明文外泄")
    void shouldMaskSecretConfig() {
        SysConfigEntity secret = new SysConfigEntity();
        secret.setConfigKey("llm.api-key");
        secret.setConfigValue("sk-abcdef123456");
        secret.setValueType("SECRET");
        secret.setCategory("llm");
        secret.setIsEncrypted(1);

        ConfigVO vo = assembler.toConfigVO(secret);

        assertThat(vo.configValue()).isEqualTo("****3456");
        assertThat(vo.encrypted()).isTrue();

        SysConfigEntity normal = new SysConfigEntity();
        normal.setConfigKey("llm.provider");
        normal.setConfigValue("mock");
        normal.setValueType("STRING");
        normal.setIsEncrypted(0);

        assertThat(assembler.toConfigVO(normal).configValue()).isEqualTo("mock");
    }

    @Test
    @DisplayName("工具日志详情保留降级原因与耗时（AC-E6）")
    void shouldKeepFallbackReasonInDetail() {
        ToolCallLogEntity entity = new ToolCallLogEntity();
        entity.setId(9L);
        entity.setTraceId("trace-1");
        entity.setOpenid("oXYZ9876543210ABC");
        entity.setToolName("manage_pet_profile");
        entity.setStatus(2);
        entity.setErrorType("L3");
        entity.setFallbackReason("物流服务不可用");
        entity.setLatencyMs(8123);

        ToolLogDetailVO detail = assembler.toToolLogDetailVO(entity);

        assertThat(detail.fallbackReason()).isEqualTo("物流服务不可用");
        assertThat(detail.latencyMs()).isEqualTo(8123);
        assertThat(detail.openid()).isEqualTo("oXYZ****0ABC");
    }

    @Test
    @DisplayName("分页装配保留 total/page/pageSize 元信息（G-10）")
    void shouldPreservePageMetadata() {
        WxUserEntity a = new WxUserEntity();
        a.setOpenid("oABC1234567890XYZ");
        WxUserEntity b = new WxUserEntity();
        b.setOpenid("oDEF1234567890UVW");

        PageResult<WxUserEntity> source = PageResult.of(List.of(a, b), 42L, 3L, 20L);
        PageResult<UserVO> mapped = assembler.assemblePage(source, assembler::toUserVO);

        assertThat(mapped.getTotal()).isEqualTo(42L);
        assertThat(mapped.getPage()).isEqualTo(3L);
        assertThat(mapped.getPageSize()).isEqualTo(20L);
        assertThat(mapped.getList()).hasSize(2);
    }

    @Test
    @DisplayName("空/空值安全：null 列表返回空列表，null 实体返回 null")
    void shouldHandleNullsSafely() {
        assertThat(assembler.mapList(null, assembler::toUserVO)).isEmpty();
        assertThat(assembler.toUserVO(null)).isNull();
        assertThat(assembler.toConfigVO(null)).isNull();
        assertThat(assembler.toPetVO(null)).isNull();
    }
}
