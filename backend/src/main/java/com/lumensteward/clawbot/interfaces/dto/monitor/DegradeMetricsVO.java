package com.lumensteward.clawbot.interfaces.dto.monitor;

import java.util.List;

/**
 * 降级与拦截看板指标（A-3 / T5，FR-17 扩展）。
 *
 * <p>核心口径：
 * <ul>
 *   <li>{@code degradeRate} = ({@code status ∈ {DEGRADED(2), TIMEOUT(3)}}) / total；</li>
 *   <li>{@code hallucinationInterceptions} = {@code log_audit} 中
 *       {@code reg_type=SAFETY && action=EXECUTION_HALLUCINATION} 的行数（执行性幻觉拦截次数）；</li>
 *   <li>{@code anomalyDistribution} = {@code log_tool_call.error_type} 按 2.3.5 四层分类归并后的分布；</li>
 *   <li>{@code externalLeakCount} = {@code log_audit} 中 {@code action=EXTERNAL_LEAK} 的行数。
 *       一致性校验在<b>发送前</b>拦截，故正常系统恒为 0，是「对外泄漏=0」的核心 KPI。</li>
 * </ul>
 *
 * @param totalCalls                 工具调用总量
 * @param degradedCalls              降级触发数（status=2 与 status=3 之和）
 * @param degradedCount              降级数（status=2）
 * @param timeoutCount               超时数（status=3）
 * @param degradeRate                降级触发率（0~1，总量为 0 时记 0）
 * @param hallucinationInterceptions 执行性幻觉拦截次数
 * @param externalLeakCount          对外泄漏次数（核心 KPI，应为 0）
 * @param leakKpiPass                对外泄漏 KPI 是否达标（externalLeakCount == 0）
 * @param anomalyDistribution        四层异常分布（L1/L2/L3/L4）
 */
public record DegradeMetricsVO(long totalCalls,
                               long degradedCalls,
                               long degradedCount,
                               long timeoutCount,
                               double degradeRate,
                               long hallucinationInterceptions,
                               long externalLeakCount,
                               boolean leakKpiPass,
                               List<AnomalyLayerVO> anomalyDistribution) {

    /**
     * 四层异常分类分布项（对齐 SRS 2.3.5）。
     *
     * @param layer 层次标识（L1/L2/L3/L4）
     * @param label 层次中文名
     * @param count 该层异常计数
     */
    public record AnomalyLayerVO(String layer, String label, long count) {
    }
}
