package com.lumensteward.clawbot.admin;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.lumensteward.clawbot.application.admin.DashboardService;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.ToolCallLogMapper;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.WxMessageMapper;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.WxUserMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 看板聚合单测（AC-E5：数值可由 SQL 直接复核，不得估算）。
 *
 * <p>以受控的 COUNT 返回值驱动，验证「成功率 = 成功数 / 总量」与各计数一一对应。
 */
class DashboardServiceTest {

    private final WxMessageMapper wxMessageMapper = mock(WxMessageMapper.class);
    private final WxUserMapper wxUserMapper = mock(WxUserMapper.class);
    private final ToolCallLogMapper toolCallLogMapper = mock(ToolCallLogMapper.class);

    @Test
    @DisplayName("AC-E5：各指标与 COUNT 结果一一对应，成功率精确计算")
    void shouldComputeSummaryFromCounts() {
        when(wxMessageMapper.selectCount(any())).thenReturn(120L);
        when(wxUserMapper.selectCount(any())).thenReturn(8L);
        // 顺序：总量、成功(0)、失败(1)、降级(2)、超时(3)
        Deque<Long> counts = new ArrayDeque<>(List.of(10L, 6L, 2L, 1L, 1L));
        when(toolCallLogMapper.selectCount(any(Wrapper.class))).thenAnswer(invocation -> counts.poll());
        when(toolCallLogMapper.selectCount(null)).thenAnswer(invocation -> counts.poll());

        DashboardService service = new DashboardService(wxMessageMapper, wxUserMapper, toolCallLogMapper);
        DashboardService.Summary summary = service.summary();

        assertThat(summary.todayMessages()).isEqualTo(120L);
        assertThat(summary.activeUsers()).isEqualTo(8L);
        assertThat(summary.toolCalls()).isEqualTo(10L);
        assertThat(summary.toolSuccess()).isEqualTo(6L);
        assertThat(summary.toolFailed()).isEqualTo(2L);
        assertThat(summary.toolDegraded()).isEqualTo(1L);
        assertThat(summary.toolTimeout()).isEqualTo(1L);
        assertThat(summary.successRate()).isEqualTo(0.6);
        assertThat(summary.degradedCount()).isEqualTo(summary.toolDegraded());
    }

    @Test
    @DisplayName("无工具调用时成功率记 0（避免除零）")
    void shouldHandleZeroToolCalls() {
        when(wxMessageMapper.selectCount(any())).thenReturn(0L);
        when(wxUserMapper.selectCount(any())).thenReturn(0L);
        when(toolCallLogMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(toolCallLogMapper.selectCount(null)).thenReturn(0L);

        DashboardService.Summary summary =
                new DashboardService(wxMessageMapper, wxUserMapper, toolCallLogMapper).summary();

        assertThat(summary.successRate()).isZero();
        assertThat(summary.toolCalls()).isZero();
    }
}
