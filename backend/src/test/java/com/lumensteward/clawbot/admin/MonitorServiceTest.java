package com.lumensteward.clawbot.admin;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.lumensteward.clawbot.application.admin.DashboardService;
import com.lumensteward.clawbot.application.admin.MonitorService;
import com.lumensteward.clawbot.application.admin.ToolLogQueryService;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.AnomalyEventMapper;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.AuditLogMapper;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.ToolCallLogMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 监控聚合单测（FR-17 / T4 + A-3 / T5）。
 *
 * <p>以 Mockito 受控桩驱动聚合返回值，验证：概览复用、趋势成功率、工具成功率、
 * 意图归因、延迟直方桶、降级触发率与四层异常分布等口径<b>与等价 SQL 一一对应</b>（AC-E5）。
 */
class MonitorServiceTest {

    private final DashboardService dashboardService = mock(DashboardService.class);
    private final ToolLogQueryService toolLogQueryService = mock(ToolLogQueryService.class);
    private final ToolCallLogMapper toolCallLogMapper = mock(ToolCallLogMapper.class);
    private final AuditLogMapper auditLogMapper = mock(AuditLogMapper.class);
    private final AnomalyEventMapper anomalyEventMapper = mock(AnomalyEventMapper.class);

    private final MonitorService service = new MonitorService(dashboardService, toolLogQueryService,
            toolCallLogMapper, auditLogMapper, anomalyEventMapper);

    @Test
    @DisplayName("概览：复用 DashboardService 计数并补以平均耗时")
    void shouldComposeOverview() {
        when(dashboardService.summary()).thenReturn(new DashboardService.Summary(
                120L, 8L, 10L, 6L, 2L, 1L, 1L, 0.6, 1L));
        when(toolLogQueryService.stats(isNull(), isNull(), isNull()))
                .thenReturn(new ToolLogQueryService.Stats(10L, 6L, 2L, 1L, 1L, 0L, 0.6, 250.0,
                        List.of()));

        MonitorService.Overview overview = service.overview();

        assertThat(overview.todayMessages()).isEqualTo(120L);
        assertThat(overview.activeUsers()).isEqualTo(8L);
        assertThat(overview.toolCalls()).isEqualTo(10L);
        assertThat(overview.successRate()).isEqualTo(0.6);
        assertThat(overview.avgLatencyMs()).isEqualTo(250.0);
        assertThat(overview.degradedCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("趋势：按桶聚合的调用量/成功数/成功率正确")
    void shouldComputeTrend() {
        Map<String, Object> row1 = new HashMap<>();
        row1.put("bucket", "2025-09-19");
        row1.put("total", 10L);
        row1.put("success", 8L);
        Map<String, Object> row2 = new HashMap<>();
        row2.put("bucket", "2025-09-20");
        row2.put("total", 4L);
        row2.put("success", 1L);
        when(toolCallLogMapper.selectMaps(any(Wrapper.class))).thenReturn(List.of(row1, row2));

        List<MonitorService.TrendPoint> points = service.trend(null, null, "DAY");

        assertThat(points).hasSize(2);
        assertThat(points.get(0).bucket()).isEqualTo("2025-09-19");
        assertThat(points.get(0).total()).isEqualTo(10L);
        assertThat(points.get(0).successRate()).isEqualTo(0.8);
        assertThat(points.get(1).successRate()).isEqualTo(0.25);
    }

    @Test
    @DisplayName("工具成功率：复用 stats 明细并计算成功率（总量为 0 记 0）")
    void shouldComputeSuccessRateByTool() {
        when(toolLogQueryService.stats(isNull(), isNull(), isNull())).thenReturn(
                new ToolLogQueryService.Stats(13L, 9L, 2L, 1L, 1L, 0L, 0.69, 100.0, List.of(
                        new ToolLogQueryService.StatItem("query_express", 10L, 8L, 1L, 1L, 0, 120.0),
                        new ToolLogQueryService.StatItem("plan_route", 0L, 0L, 0L, 0L, 0, 0.0))));

        List<MonitorService.SuccessRate> items = service.successRateByTool(null, null);

        assertThat(items).hasSize(2);
        assertThat(items.get(0).toolName()).isEqualTo("query_express");
        assertThat(items.get(0).successRate()).isEqualTo(0.8);
        assertThat(items.get(1).successRate()).isZero();
    }

    @Test
    @DisplayName("意图分布：工具名归因到意图域，未匹配归入 chat，缺失域补 0")
    void shouldComputeIntentDistribution() {
        Map<String, Object> r1 = new HashMap<>();
        r1.put("tool_name", "query_express");
        r1.put("total", 3L);
        Map<String, Object> r2 = new HashMap<>();
        r2.put("tool_name", "plan_route");
        r2.put("total", 2L);
        Map<String, Object> r3 = new HashMap<>();
        r3.put("tool_name", "some_future_tool");
        r3.put("total", 1L);
        when(toolCallLogMapper.selectMaps(any(Wrapper.class))).thenReturn(List.of(r1, r2, r3));

        List<MonitorService.IntentSlice> slices = service.intentDistribution(null, null);

        Map<String, Long> byIntent = new HashMap<>();
        slices.forEach(slice -> byIntent.put(slice.intent(), slice.count()));
        assertThat(byIntent).containsEntry("express", 3L)
                .containsEntry("navigation", 2L)
                .containsEntry("chat", 1L)
                .containsEntry("image_recognition", 0L)
                .containsEntry("tts", 0L)
                .containsEntry("pet_profile", 0L);
        assertThat(slices.get(0).intent()).isEqualTo("image_recognition");
    }

    @Test
    @DisplayName("延迟直方：固定 5 桶且空行不抛异常")
    void shouldComputeLatencyDistribution() {
        Map<String, Object> row = new HashMap<>();
        row.put("b0", 2L);
        row.put("b1", 3L);
        row.put("b2", 1L);
        row.put("b3", 0L);
        row.put("b4", 1L);
        when(toolCallLogMapper.selectMaps(any(Wrapper.class))).thenReturn(List.of(row));

        List<MonitorService.LatencyBucket> buckets = service.latencyDistribution(null, null);

        assertThat(buckets).hasSize(5);
        assertThat(buckets.get(0).range()).isEqualTo("<100");
        assertThat(buckets.get(0).count()).isEqualTo(2L);
        assertThat(buckets.get(3).count()).isZero();
        assertThat(buckets.get(4).count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("延迟直方：无数据行时各桶记 0")
    void shouldHandleEmptyLatencyRow() {
        when(toolCallLogMapper.selectMaps(any(Wrapper.class))).thenReturn(List.of());

        List<MonitorService.LatencyBucket> buckets = service.latencyDistribution(null, null);

        assertThat(buckets).hasSize(5);
        assertThat(buckets).allMatch(bucket -> bucket.count() == 0L);
    }

    @Test
    @DisplayName("降级看板：降级触发率、幻觉拦截、四层分布、泄漏 KPI 正确")
    void shouldComputeDegradeMetrics() {
        // countByStatus 调用顺序：总量、降级(status=2)、超时(status=3)
        Deque<Long> counts = new ArrayDeque<>(List.of(20L, 3L, 2L));
        when(toolCallLogMapper.selectCount(any(Wrapper.class))).thenAnswer(invocation -> counts.poll());

        // 审计计数顺序：幻觉拦截、对外泄漏
        Deque<Long> auditCounts = new ArrayDeque<>(List.of(5L, 0L));
        when(auditLogMapper.selectCount(any(Wrapper.class)))
                .thenAnswer(invocation -> auditCounts.poll());

        // 四层分布原始行：L3 工具层错误码两类
        Map<String, Object> e1 = new HashMap<>();
        e1.put("error_type", "TOOL_NOT_FOUND");
        e1.put("total", 4L);
        Map<String, Object> e2 = new HashMap<>();
        e2.put("error_type", "TOOL_TIMEOUT");
        e2.put("total", 2L);
        when(toolCallLogMapper.selectMaps(any(Wrapper.class))).thenReturn(List.of(e1, e2));

        MonitorService.DegradeMetrics metrics = service.degradeMetrics(null, null);

        assertThat(metrics.totalCalls()).isEqualTo(20L);
        assertThat(metrics.degradedCount()).isEqualTo(3L);
        assertThat(metrics.timeoutCount()).isEqualTo(2L);
        assertThat(metrics.degradedCalls()).isEqualTo(5L);
        assertThat(metrics.degradeRate()).isEqualTo(0.25);
        assertThat(metrics.hallucinationInterceptions()).isEqualTo(5L);
        assertThat(metrics.externalLeakCount()).isZero();
        assertThat(metrics.leakKpiPass()).isTrue();
        assertThat(metrics.anomalyDistribution()).hasSize(4);
        Map<String, Long> byLayer = new HashMap<>();
        metrics.anomalyDistribution().forEach(layer -> byLayer.put(layer.layer(), layer.count()));
        assertThat(byLayer).containsEntry("L1", 0L)
                .containsEntry("L2", 0L)
                .containsEntry("L3", 6L)
                .containsEntry("L4", 0L);
    }

    @Test
    @DisplayName("四层分布：L1/L2 由 log_anomaly_event 显式层次合并计入（W1）")
    void shouldMergeAnomalyEventLayersIntoDistribution() {
        when(toolCallLogMapper.selectCount(any(Wrapper.class))).thenReturn(10L);
        Map<String, Object> toolErr = new HashMap<>();
        toolErr.put("error_type", "TOOL_TIMEOUT");
        toolErr.put("total", 3L);
        when(toolCallLogMapper.selectMaps(any(Wrapper.class))).thenReturn(List.of(toolErr));

        Map<String, Object> l1 = new HashMap<>();
        l1.put("layer", "L1");
        l1.put("total", 4L);
        Map<String, Object> l2 = new HashMap<>();
        l2.put("layer", "L2");
        l2.put("total", 2L);
        Map<String, Object> unknownLayer = new HashMap<>();
        unknownLayer.put("layer", "L9");
        when(anomalyEventMapper.selectMaps(any(Wrapper.class)))
                .thenReturn(List.of(l1, l2, unknownLayer));

        MonitorService.DegradeMetrics metrics = service.degradeMetrics(null, null);

        Map<String, Long> byLayer = new HashMap<>();
        metrics.anomalyDistribution().forEach(layer -> byLayer.put(layer.layer(), layer.count()));
        assertThat(byLayer).containsEntry("L1", 4L)
                .containsEntry("L2", 2L)
                .containsEntry("L3", 3L)
                .containsEntry("L4", 0L);
    }

    @Test
    @DisplayName("降级看板：无调用时降级率记 0（避免除零）")
    void shouldHandleZeroCalls() {
        when(toolCallLogMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(auditLogMapper.selectCount(any(Wrapper.class))).thenReturn(0L);
        when(toolCallLogMapper.selectMaps(any(Wrapper.class))).thenReturn(List.of());

        MonitorService.DegradeMetrics metrics = service.degradeMetrics(null, null);

        assertThat(metrics.degradeRate()).isZero();
        assertThat(metrics.leakKpiPass()).isTrue();
    }
}
