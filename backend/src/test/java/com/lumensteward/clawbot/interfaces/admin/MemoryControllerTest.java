package com.lumensteward.clawbot.interfaces.admin;

import com.lumensteward.clawbot.application.admin.MemoryAdminService;
import com.lumensteward.clawbot.common.api.ApiResponse;
import com.lumensteward.clawbot.common.api.PageQuery;
import com.lumensteward.clawbot.common.api.PageResult;
import com.lumensteward.clawbot.infrastructure.persistence.entity.MemoryItemEntity;
import com.lumensteward.clawbot.infrastructure.security.AuthPrincipal;
import com.lumensteward.clawbot.interfaces.assembler.MaskingAssembler;
import com.lumensteward.clawbot.interfaces.dto.memory.MemoryItemVO;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 状态库后台控制器测试（W6-b）。
 *
 * <p>用<b>真实</b> {@link MaskingAssembler} 验证出参 openid 已脱敏（BR-21），
 * 并验证删除动作透传操作人与来源 IP。
 */
class MemoryControllerTest {

    private final MemoryAdminService service = mock(MemoryAdminService.class);
    private final MemoryController controller = new MemoryController(service, new MaskingAssembler());

    @Test
    @DisplayName("列表出参：openid 脱敏、溯源与计数字段透出")
    void shouldMaskOpenidInList() {
        MemoryItemEntity entity = new MemoryItemEntity();
        entity.setId(3L);
        entity.setOpenid("wx-openid-a1");
        entity.setKind("PERSON");
        entity.setName("奶奶");
        entity.setContent("住杭州");
        entity.setOrigin("AUTO_EXTRACT");
        entity.setExtractor("llm-extract-v1");
        entity.setConfidence(new BigDecimal("0.800"));
        entity.setSourceSessionId(42L);
        entity.setSourceTraceId("trace-1");
        entity.setStatus("ACTIVE");
        entity.setHitCount(2);
        when(service.page(org.mockito.ArgumentMatchers.any(), eq("wx-openid-a1"), eq("PERSON"), eq("ACTIVE")))
                .thenReturn(PageResult.of(List.of(entity), 1L, 1, 20));

        ApiResponse<PageResult<MemoryItemVO>> response =
                controller.list(new PageQuery(1, 20), "wx-openid-a1", "PERSON", "ACTIVE");

        assertThat(response.getData()).isNotNull();
        List<MemoryItemVO> list = response.getData().getList();
        assertThat(list).hasSize(1);
        assertThat(list.get(0).openid()).isEqualTo("wx-o****d-a1");
        assertThat(list.get(0).sourceTraceId()).isEqualTo("trace-1");
        assertThat(list.get(0).hitCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("删除：透传操作人 adminId 与来源 IP")
    void shouldPassAdminIdAndIp() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("10.0.0.1");

        controller.delete(9L, new AuthPrincipal(2L, "root", "SUPER_ADMIN", "超管", "jti"), request);

        verify(service).delete(eq(9L), eq(2L), eq("10.0.0.1"));
    }

    @Test
    @DisplayName("删除：无主体（异常场景）时 adminId 为 null 而非 NPE")
    void shouldTolerateNullPrincipal() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("10.0.0.2");

        controller.delete(9L, null, request);

        verify(service).delete(eq(9L), eq(null), eq("10.0.0.2"));
    }
}
