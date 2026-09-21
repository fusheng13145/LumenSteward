package com.lumensteward.clawbot.application.gray;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 灰度熔断的窗口健康度快照（FR-22 / W2）。
 *
 * @param windowMinutes 观测窗口（分钟）
 * @param turns         窗口内编排链路轮次（分母）
 * @param anomalies     窗口内 L2~L4 异常数（分子；L1 属接入层噪声，与灰度功能健康无关，不计入）
 * @param errorRatePercent 错误率百分比（{@code turns=0} 时为 0；异常可多轮叠加，故可能 &gt; 100）
 * @param p95LatencyMs  窗口内链路总耗时 P95（无样本时为 0）
 * @param exceededBudget 窗口内超预算链路数（仅供后台观测，不参与熔断）
 * @param sampledAt     取样时间
 */
public record GrayHealthSnapshot(int windowMinutes,
                                 long turns,
                                 long anomalies,
                                 BigDecimal errorRatePercent,
                                 int p95LatencyMs,
                                 long exceededBudget,
                                 LocalDateTime sampledAt) {

    /**
     * 空窗（无任何链路样本）。
     *
     * @param windowMinutes 窗口分钟数
     * @return 全零快照
     */
    public static GrayHealthSnapshot empty(int windowMinutes) {
        return new GrayHealthSnapshot(windowMinutes, 0L, 0L, BigDecimal.ZERO, 0, 0L,
                LocalDateTime.now());
    }
}
