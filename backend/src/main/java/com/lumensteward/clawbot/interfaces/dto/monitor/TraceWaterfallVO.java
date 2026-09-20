package com.lumensteward.clawbot.interfaces.dto.monitor;

import java.util.List;

/**
 * 单次链路时序瀑布视图（A-5 / T6，GET /api/monitor/trace/{traceId}）。
 *
 * <p>对 {@code log_orchestration_trace.span_json} 的反序列化封装：{@code spans} 为按时间轴
 * 排列的 span 列表，前端据此绘制瀑布图；顶部字段给出链路总耗时、预算与是否超预算（SC-03）。
 *
 * @param traceId        链路标识
 * @param totalMs        链路总耗时（ms）
 * @param totalBudgetMs  链路总时间预算（ms，SC-03）
 * @param rounds         Agent Loop 轮次
 * @param exceededBudget 是否超出总预算
 * @param spans          时序 span 列表
 */
public record TraceWaterfallVO(String traceId, long totalMs, int totalBudgetMs, int rounds,
                               boolean exceededBudget, List<SpanVO> spans) {

    /**
     * 时序 span（与 {@code OrchestrationSpan} 字段同构，供前端直读）。
     *
     * @param kind          span 类型（{@code LLM_ROUND} / {@code TOOL}）
     * @param seq           链路内顺序号（从 1 递增）
     * @param round         所属 Agent Loop 轮次
     * @param name          span 名称（工具名或 {@code "LLM#round"}）
     * @param startOffsetMs 相对链路起点的开始偏移（ms）
     * @param durationMs    持续耗时（ms）
     * @param status        执行状态（{@code OK} / {@code FAIL} / {@code DEGRADED} / {@code TIMEOUT}）
     */
    public record SpanVO(String kind, int seq, int round, String name, long startOffsetMs,
                         long durationMs, String status) {
    }
}
