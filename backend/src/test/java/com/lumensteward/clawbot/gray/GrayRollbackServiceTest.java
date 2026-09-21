package com.lumensteward.clawbot.gray;

import com.lumensteward.clawbot.application.admin.ConfigAdminService;
import com.lumensteward.clawbot.application.gray.GrayFeature;
import com.lumensteward.clawbot.application.gray.GrayReleaseService;
import com.lumensteward.clawbot.application.gray.GrayRollbackService;
import com.lumensteward.clawbot.infrastructure.persistence.service.AuditLogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 灰度一键回滚单测（FR-22 AC②③ / BR-31）。
 *
 * <p>要证明的是「回滚走的是配置写路径」：比例置 0 经 {@link ConfigAdminService}，
 * 于是热生效、值校验、逐项 CONFIG_UPDATE 留痕一条都不缺；本服务只额外补一行 GRAY 汇总审计。
 */
class GrayRollbackServiceTest {

    private final ConfigAdminService configAdmin = mock(ConfigAdminService.class);
    private final GrayReleaseService grayRelease = mock(GrayReleaseService.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);

    private final GrayRollbackService service =
            new GrayRollbackService(configAdmin, grayRelease, auditLogService);

    @Test
    @DisplayName("在放量的功能：写成 0 + 原因带触发条件 + 操作人/IP 透传 + 补一行 GRAY 汇总审计")
    void rollsBackActiveFeatures() {
        when(grayRelease.percent(GrayFeature.MEMORY_GROWTH)).thenReturn(20);

        GrayRollbackService.RollbackResult result =
                service.rollbackAll("错误率 45% > 30%", 7L, "10.0.0.9");

        assertThat(result.rolledBack()).isEqualTo(1);
        assertThat(result.fromPercent()).containsExactly("memory_growth=20");

        ArgumentCaptor<List<ConfigAdminService.ConfigItem>> items = itemListCaptor();
        verify(configAdmin).update(items.capture(), eq("灰度回滚：错误率 45% > 30%"), eq(7L), eq("10.0.0.9"));
        assertThat(items.getValue()).allSatisfy(item -> assertThat(item.configValue()).isEqualTo("0"));
        assertThat(items.getValue()).extracting(ConfigAdminService.ConfigItem::configKey)
                .contains(GrayFeature.MEMORY_GROWTH.percentKey());

        verify(auditLogService).record(eq(7L), eq("GRAY"), eq("ROLLBACK"), eq("gray.*.percent"),
                eq("memory_growth=20"), eq("percent=0"), eq("错误率 45% > 30%"), eq("10.0.0.9"), eq(1));
    }

    @Test
    @DisplayName("本就全 0（重复回滚 / 未放量）：不写配置、不写审计，避免制造噪音记录")
    void skipsWhenNothingIsRollingOut() {
        when(grayRelease.percent(any(GrayFeature.class))).thenReturn(0);

        GrayRollbackService.RollbackResult result = service.rollbackAll("演练", null, null);

        assertThat(result.rolledBack()).isZero();
        assertThat(result.fromPercent()).isEmpty();
        verify(configAdmin, never()).update(anyList(), anyString(), any(), any());
        verify(auditLogService, never()).record(any(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), any(), anyInt());
    }

    @Test
    @DisplayName("系统触发（无操作人）：adminId 为 null，log_audit 该行仍写成功（admin_id 允许 NULL）")
    void supportsSystemTrigger() {
        when(grayRelease.percent(GrayFeature.MEMORY_GROWTH)).thenReturn(100);

        service.rollbackAll("熔断自动回滚", null, null);

        verify(auditLogService).record(eq(null), eq("GRAY"), eq("ROLLBACK"), eq("gray.*.percent"),
                anyString(), anyString(), eq("熔断自动回滚"), eq(null), eq(1));
    }

    @Test
    @DisplayName("回滚已成功但审计写失败：不把回滚动作本身推翻（配置已置 0，异常不外溢）")
    void keepsRollbackWhenAuditFails() {
        when(grayRelease.percent(GrayFeature.MEMORY_GROWTH)).thenReturn(5);
        doThrow(new IllegalStateException("audit db down"))
                .when(auditLogService).record(any(), anyString(), anyString(), anyString(),
                        anyString(), anyString(), anyString(), any(), anyInt());

        assertThat(service.rollbackAll("异常流", 1L, "127.0.0.1").rolledBack()).isEqualTo(1);
        verify(configAdmin).update(anyList(), anyString(), eq(1L), eq("127.0.0.1"));
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<ConfigAdminService.ConfigItem>> itemListCaptor() {
        return ArgumentCaptor.forClass(List.class);
    }
}
