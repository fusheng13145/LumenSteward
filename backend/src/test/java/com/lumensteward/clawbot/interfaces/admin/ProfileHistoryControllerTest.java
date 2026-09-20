package com.lumensteward.clawbot.interfaces.admin;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumensteward.clawbot.application.admin.AuditLogQueryService;
import com.lumensteward.clawbot.common.api.ApiResponse;
import com.lumensteward.clawbot.common.api.PageQuery;
import com.lumensteward.clawbot.common.api.PageResult;
import com.lumensteward.clawbot.common.util.MaskUtils;
import com.lumensteward.clawbot.infrastructure.persistence.entity.AuditLogEntity;
import com.lumensteward.clawbot.interfaces.assembler.MaskingAssembler;
import com.lumensteward.clawbot.interfaces.dto.audit.AuditLogVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 档案变更留痕控制器测试（A-4 / T7）。
 *
 * <p>验证 {@link ProfileHistoryController} 固定取 {@code reg_type=PROFILE}，
 * 并将 {@code openid} 脱敏后匹配 {@code target} 列。复用 {@link AuditLogQueryService} 与
 * {@link MaskingAssembler}（以 Mockito 桩替代，避免依赖 DB/Spring 上下文）。
 */
class ProfileHistoryControllerTest {

    private final AuditLogQueryService queryService = mock(AuditLogQueryService.class);
    private final MaskingAssembler maskingAssembler = mock(MaskingAssembler.class);
    private final ProfileHistoryController controller =
            new ProfileHistoryController(queryService, maskingAssembler);

    @Test
    @DisplayName("列表查询固定取 reg_type=PROFILE，并按脱敏 target 过滤")
    void shouldQueryProfileRegTypeAndMaskTarget() {
        PageQuery query = new PageQuery(1, 20);
        when(queryService.page(any(), any(), any(), any(), any(), any()))
                .thenReturn(PageResult.from(new Page<AuditLogEntity>()));
        AuditLogVO vo = new AuditLogVO(1L, null, "PROFILE", "UPDATE", "open****user",
                "{\"pet_type\":\"猫\"}", "{\"pet_type\":\"狗\"}", "原因", "1.2.3.4", 1, null);
        when(maskingAssembler.assemblePage(any(), any()))
                .thenReturn(PageResult.of(List.of(vo), 1L, 1, 20));

        String rawOpenid = "openid-raw-user";
        ApiResponse<PageResult<AuditLogVO>> response = controller.list(query, rawOpenid, "UPDATE");

        verify(queryService).page(eq(query), eq("PROFILE"), eq("UPDATE"),
                eq(MaskUtils.openid(rawOpenid)), any(), any());
        assertThat(response.getData()).isNotNull();
        assertThat(response.getData().getList()).hasSize(1);
    }

    @Test
    @DisplayName("不传 openid 时不过滤 target（target=null）")
    void shouldNotFilterWhenOpenidAbsent() {
        PageQuery query = new PageQuery();
        when(queryService.page(any(), any(), any(), any(), any(), any()))
                .thenReturn(PageResult.from(new Page<AuditLogEntity>()));
        when(maskingAssembler.assemblePage(any(), any()))
                .thenReturn(PageResult.of(List.of(), 0L, 1, 20));

        controller.list(query, null, null);

        verify(queryService).page(any(), eq("PROFILE"), any(), isNull(), any(), any());
    }
}
