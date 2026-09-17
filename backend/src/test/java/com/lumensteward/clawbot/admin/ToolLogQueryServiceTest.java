package com.lumensteward.clawbot.admin;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.lumensteward.clawbot.application.admin.ToolLogQueryService;
import com.lumensteward.clawbot.infrastructure.persistence.entity.ToolCallLogEntity;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.ToolCallLogMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 工具日志检索与统计单测（AC-E6：调用量/成功率/耗时分布）。
 *
 * <p>验证统计口径与聚合行解析；数据全部来自 Mockito 受控桩，无需 DB。
 */
class ToolLogQueryServiceTest {

    private final ToolCallLogMapper mapper = mock(ToolCallLogMapper.class);
    private final ToolLogQueryService service = new ToolLogQueryService(mapper);

    @Test
    @DisplayName("统计：总量/各状态计数/成功率/加权平均耗时 与聚合结果一致")
    void shouldComputeStats() {
        // 顺序：总量、成功、失败、降级、超时、未执行
        Deque<Long> counts = new ArrayDeque<>(List.of(10L, 6L, 2L, 1L, 1L, 0L));
        when(mapper.selectCount(any(Wrapper.class))).thenAnswer(invocation -> counts.poll());

        Map<String, Object> row = new HashMap<>();
        row.put("tool_name", "manage_pet_profile");
        row.put("total", 10L);
        row.put("success", 6L);
        row.put("failed", 2L);
        row.put("degraded", 1L);
        row.put("timeout", 1L);
        row.put("avg_latency", 250.0);
        when(mapper.selectMaps(any(Wrapper.class))).thenReturn(List.of(row));

        ToolLogQueryService.Stats stats = service.stats(null, null, null);

        assertThat(stats.total()).isEqualTo(10L);
        assertThat(stats.success()).isEqualTo(6L);
        assertThat(stats.failed()).isEqualTo(2L);
        assertThat(stats.degraded()).isEqualTo(1L);
        assertThat(stats.timeout()).isEqualTo(1L);
        assertThat(stats.notExecuted()).isZero();
        assertThat(stats.successRate()).isEqualTo(0.6);
        assertThat(stats.avgLatencyMs()).isEqualTo(250.0);
        assertThat(stats.items()).hasSize(1);
        assertThat(stats.items().get(0).toolName()).isEqualTo("manage_pet_profile");
    }

    @Test
    @DisplayName("统计：空聚合行时平均耗时为 0，不抛异常")
    void shouldHandleEmptyAggregation() {
        when(mapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(mapper.selectMaps(any(Wrapper.class))).thenReturn(List.of());

        ToolLogQueryService.Stats stats = service.stats("no-such-tool", null, null);

        assertThat(stats.total()).isZero();
        assertThat(stats.successRate()).isZero();
        assertThat(stats.avgLatencyMs()).isZero();
        assertThat(stats.items()).isEmpty();
    }

    @Test
    @DisplayName("详情：命中与未命中（返回 Optional.empty 而非 null）")
    void shouldFindByIdSafely() {
        ToolCallLogEntity entity = new ToolCallLogEntity();
        entity.setId(9L);
        entity.setToolName("manage_pet_profile");
        when(mapper.selectById(9L)).thenReturn(entity);

        assertThat(service.findById(9L)).contains(entity);
        assertThat(service.findById(null)).isEmpty();
        assertThat(service.findById(404L)).isEqualTo(Optional.empty());
    }
}
