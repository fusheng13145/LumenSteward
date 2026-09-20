package com.lumensteward.clawbot.application.orchestrator;

import com.lumensteward.clawbot.application.orchestrator.model.OrchestrationSpan;
import com.lumensteward.clawbot.common.util.MaskUtils;

import java.util.List;

/**
 * 链路时序追踪事件（A-5 超时预算 / 迭代 3 Wave2 T6）。
 *
 * <p>由 {@code AgentOrchestratorImpl} 在<b>一次编排链路结束时</b>经
 * {@code ApplicationEventPublisher} 发布；{@code OrchestrationTraceListener} 监听后落库
 * {@code log_orchestration_trace}，管理后台据 {@code traceId} 查询并渲染时序瀑布图。
 *
 * <p>与 {@code ConsoleEvent} 同范式：{@code openid} 在构造时即<b>脱敏</b>（BR-21），事件对象
 * 全程不含明文用户标识；落库异常经监听器捕获，<b>不影响主链路</b>。
 *
 * @param traceId        链路标识（与响应头 {@code X-Trace-Id} 同源）
 * @param openid         用户标识（构造内脱敏）
 * @param sessionId      会话 id（可空）
 * @param totalMs        链路总耗时（ms）= 最后一个 span 结束相对起点的偏移
 * @param totalBudgetMs  链路总时间预算（ms，SC-03）
 * @param rounds         Agent Loop 轮次
 * @param exceededBudget 是否超出总预算（{@code totalMs > totalBudgetMs}）
 * @param spans          链路时序 span 列表（按时间顺序）
 */
public record OrchestrationTracedEvent(String traceId, String openid, Long sessionId, long totalMs,
                                       int totalBudgetMs, int rounds, boolean exceededBudget,
                                       List<OrchestrationSpan> spans) {

    /**
     * 规范构造：脱敏 openid，防御性拷贝 span 列表。
     *
     * @param traceId        链路标识
     * @param openid         原始用户标识（本构造内脱敏）
     * @param sessionId      会话 id
     * @param totalMs        链路总耗时
     * @param totalBudgetMs  链路预算
     * @param rounds         轮次
     * @param exceededBudget 是否超预算
     * @param spans          span 列表
     */
    public OrchestrationTracedEvent {
        openid = MaskUtils.openid(openid);
        spans = spans == null ? List.of() : List.copyOf(spans);
    }
}
