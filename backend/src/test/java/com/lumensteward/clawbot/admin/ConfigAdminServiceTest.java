package com.lumensteward.clawbot.admin;

import com.lumensteward.clawbot.application.admin.ConfigAdminService;
import com.lumensteward.clawbot.common.exception.BizException;
import com.lumensteward.clawbot.domain.tool.ToolRegistry;
import com.lumensteward.clawbot.infrastructure.cache.ConfigCacheService;
import com.lumensteward.clawbot.infrastructure.persistence.entity.SysConfigEntity;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.SysConfigMapper;
import com.lumensteward.clawbot.infrastructure.persistence.service.AuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 配置写服务单测（FR-18 / T10）。
 *
 * <p>逐条对齐四条验收：①免重启生效（缓存失效）②密钥不回明 ③前后值留痕 ④非法值被拒。
 */
class ConfigAdminServiceTest {

    private final SysConfigMapper mapper = mock(SysConfigMapper.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final ConfigCacheService cache = mock(ConfigCacheService.class);
    private final ToolRegistry toolRegistry = mock(ToolRegistry.class);

    private final ConfigAdminService service =
            new ConfigAdminService(mapper, auditLogService, cache, toolRegistry);

    @BeforeEach
    void setUp() {
        when(toolRegistry.names()).thenReturn(Set.of("plan_route", "query_express", "manage_pet_profile"));
    }

    @Test
    @DisplayName("AC① 更新后即失效缓存，下一次读取为新值（免重启生效）")
    void shouldInvalidateCacheAfterUpdate() {
        when(mapper.selectOne(any())).thenReturn(entity("llm.model", "mock-model", "STRING"));

        service.update(List.of(new ConfigAdminService.ConfigItem("llm.model", "gpt-4o-mini")),
                "切换模型", 1L, "127.0.0.1");

        verify(cache).invalidate("llm.model");
        ArgumentCaptor<SysConfigEntity> captor = ArgumentCaptor.forClass(SysConfigEntity.class);
        verify(mapper).updateById(captor.capture());
        assertThat(captor.getValue().getConfigValue()).isEqualTo("gpt-4o-mini");
    }

    @Test
    @DisplayName("AC③ 非密钥配置：前后值原样留痕，可比对")
    void shouldAuditBeforeAfterForPlainValue() {
        when(mapper.selectOne(any())).thenReturn(entity("llm.model", "mock-model", "STRING"));

        service.update(List.of(new ConfigAdminService.ConfigItem("llm.model", "gpt-4o-mini")),
                "切换模型", 1L, "127.0.0.1");

        verify(auditLogService).record(eq(1L), eq("CONFIG"), eq("CONFIG_UPDATE"), eq("llm.model"),
                eq("mock-model"), eq("gpt-4o-mini"), eq("切换模型"), eq("127.0.0.1"), eq(1));
    }

    @Test
    @DisplayName("AC② 密钥配置：留痕仅尾号，不落明文")
    void shouldMaskSecretInAudit() {
        when(mapper.selectOne(any())).thenReturn(entity("wx.token", "oldtoken", "SECRET", 1));

        service.update(List.of(new ConfigAdminService.ConfigItem("wx.token", "abcdefgh1234")),
                "轮换 Token", 1L, "127.0.0.1");

        ArgumentCaptor<String> beforeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> afterCaptor = ArgumentCaptor.forClass(String.class);
        verify(auditLogService).record(eq(1L), eq("CONFIG"), eq("CONFIG_UPDATE"), eq("wx.token"),
                beforeCaptor.capture(), afterCaptor.capture(), eq("轮换 Token"), eq("127.0.0.1"), eq(1));
        assertThat(beforeCaptor.getValue()).doesNotContain("oldtoken");
        assertThat(afterCaptor.getValue()).isEqualTo("****1234");
    }

    @Test
    @DisplayName("AC④ 非法值被拒：INT 传非整数")
    void shouldRejectNonInteger() {
        when(mapper.selectOne(any())).thenReturn(entity("orchestration.max-rounds", "5", "INT"));

        assertThatThrownBy(() -> service.update(
                List.of(new ConfigAdminService.ConfigItem("orchestration.max-rounds", "five")),
                "调整轮次", 1L, "127.0.0.1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("必须为整数");
        verify(mapper, never()).updateById(any(SysConfigEntity.class));
    }

    @Test
    @DisplayName("AC④ 非法值被拒：BOOL 传非布尔")
    void shouldRejectNonBoolean() {
        when(mapper.selectOne(any())).thenReturn(entity("safety.strict-mode", "false", "BOOL"));

        assertThatThrownBy(() -> service.update(
                List.of(new ConfigAdminService.ConfigItem("safety.strict-mode", "perhaps")),
                "开启严格模式", 1L, "127.0.0.1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("必须为 true/false");
    }

    @Test
    @DisplayName("AC④ 非法值被拒：JSON 传非法串")
    void shouldRejectInvalidJson() {
        when(mapper.selectOne(any())).thenReturn(entity("orchestration.disabled-tools", "[]", "JSON"));

        assertThatThrownBy(() -> service.update(
                List.of(new ConfigAdminService.ConfigItem("orchestration.disabled-tools", "[plan_route")),
                "禁用导航", 1L, "127.0.0.1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("合法 JSON");
    }

    @Test
    @DisplayName("AC④ 工具开关含未注册工具名 → 拒绝（杜绝静默无效）")
    void shouldRejectUnknownToolName() {
        when(mapper.selectOne(any())).thenReturn(entity("orchestration.disabled-tools", "[]", "JSON"));

        assertThatThrownBy(() -> service.update(
                List.of(new ConfigAdminService.ConfigItem("orchestration.disabled-tools", "[\"ghost_tool\"]")),
                "禁用不存在的工具", 1L, "127.0.0.1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未注册工具名");
        verify(mapper, never()).updateById(any(SysConfigEntity.class));
    }

    @Test
    @DisplayName("AC④ 工具开关为已注册工具名 → 放行")
    void shouldAcceptRegisteredToolName() {
        when(mapper.selectOne(any())).thenReturn(entity("orchestration.disabled-tools", "[]", "JSON"));

        assertThatCode(() -> service.update(
                List.of(new ConfigAdminService.ConfigItem("orchestration.disabled-tools", "[\"plan_route\"]")),
                "临时下线导航", 1L, "127.0.0.1")).doesNotThrowAnyException();
        verify(cache).invalidate("orchestration.disabled-tools");
    }

    @Test
    @DisplayName("不存在的配置键 → 拒绝（防止凭空造配置项）")
    void shouldRejectUnknownKey() {
        when(mapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.update(
                List.of(new ConfigAdminService.ConfigItem("no.such.key", "x")), "新增", 1L, "127.0.0.1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("配置项不存在");
    }

    @Test
    @DisplayName("变更原因必填（BR-25）")
    void shouldRequireReason() {
        when(mapper.selectOne(any())).thenReturn(entity("llm.model", "mock-model", "STRING"));

        assertThatThrownBy(() -> service.update(
                List.of(new ConfigAdminService.ConfigItem("llm.model", "x")), "  ", 1L, "127.0.0.1"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("变更原因必填");
    }

    @Test
    @DisplayName("恢复默认值：写回 default_value 并失效缓存")
    void shouldResetToDefault() {
        SysConfigEntity entity = entity("llm.model", "gpt-4o-mini", "STRING");
        entity.setDefaultValue("mock-model");
        when(mapper.selectOne(any())).thenReturn(entity);

        service.reset("llm.model", 1L, "127.0.0.1");

        ArgumentCaptor<SysConfigEntity> captor = ArgumentCaptor.forClass(SysConfigEntity.class);
        verify(mapper).updateById(captor.capture());
        assertThat(captor.getValue().getConfigValue()).isEqualTo("mock-model");
        verify(cache).invalidate("llm.model");
        verify(auditLogService).record(eq(1L), eq("CONFIG"), eq("CONFIG_RESET"), eq("llm.model"),
                eq("gpt-4o-mini"), eq("mock-model"), eq("恢复默认值"), eq("127.0.0.1"), eq(1));
    }

    private static SysConfigEntity entity(String key, String value, String type) {
        return entity(key, value, type, 0);
    }

    private static SysConfigEntity entity(String key, String value, String type, int encrypted) {
        SysConfigEntity entity = new SysConfigEntity();
        entity.setId(1L);
        entity.setConfigKey(key);
        entity.setConfigValue(value);
        entity.setValueType(type);
        entity.setDefaultValue(value);
        entity.setIsEncrypted(encrypted);
        entity.setCategory("llm");
        entity.setDescription("测试用配置项");
        return entity;
    }
}
