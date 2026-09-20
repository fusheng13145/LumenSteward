package com.lumensteward.clawbot.application.orchestrator.model;

/**
 * 编排链路时序 span（A-5 超时预算 / 迭代 3 Wave2 T6）。
 *
 * <p>一次编排链路（单条用户消息从进入 Agent Loop 到产出回复）由若干<b>span</b> 构成：
 * 每轮一次 LLM 调用记一个 {@link SpanKind#LLM_ROUND}，每次工具调用记一个 {@link SpanKind#TOOL}。
 * 所有时间均以<b>链路起点</b>为原点的相对量，供管理后台渲染「单次链路时序瀑布图」。
 *
 * <p>时间轴口径（AC：起止 / 相对偏移 / 耗时自洽）：
 * <ul>
 *   <li>{@code startOffsetMs}：本 span 开始时间相对链路起点的偏移（ms，&ge;0）；</li>
 *   <li>{@code durationMs}：本 span 持续时间（ms，&ge;0）；</li>
 *   <li>结束时间 = {@code startOffsetMs + durationMs}，不超过链路 {@code totalMs}。</li>
 * </ul>
 *
 * @param kind           span 类型（LLM 轮次 / 工具调用）
 * @param seq            链路内 span 顺序号（从 1 递增，用于瀑布图纵轴排序）
 * @param round          所属 Agent Loop 轮次（0 基；SC-01）
 * @param name           span 名称（工具名，或 {@code "LLM#round"}）
 * @param startOffsetMs  相对链路起点的开始偏移（ms）
 * @param durationMs     持续耗时（ms）
 * @param status         执行状态
 */
public record OrchestrationSpan(SpanKind kind, int seq, int round, String name,
                                long startOffsetMs, long durationMs, SpanStatus status) {

    /** span 类型。 */
    public enum SpanKind {
        /** 一轮 LLM 调用（Agent Loop 单轮）。 */
        LLM_ROUND,
        /** 一次工具调用。 */
        TOOL
    }

    /**
     * span 执行状态。
     *
     * <p>对齐 4.4.2 时序语义：正常 {@code OK}；工具失败 {@code FAIL}；返回兜底 {@code DEGRADED}；
     * 触达单工具/链路超时 {@code TIMEOUT}。
     */
    public enum SpanStatus {
        /** 成功。 */
        OK,
        /** 失败（含工具未注册 / 参数非法等未执行情况）。 */
        FAIL,
        /** 降级（返回兜底结果）。 */
        DEGRADED,
        /** 超时。 */
        TIMEOUT
    }
}
