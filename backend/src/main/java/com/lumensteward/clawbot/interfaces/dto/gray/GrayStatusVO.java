package com.lumensteward.clawbot.interfaces.dto.gray;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 灰度状态视图（FR-22 / W2 的「可观测」侧）。
 *
 * @param features 各灰度功能当前放量口径
 * @param breaker  熔断阈值与开关
 * @param health   熔断所依据的近窗健康度（与熔断器同一取数口径）
 */
public record GrayStatusVO(List<Feature> features, Breaker breaker, Health health) {

    /**
     * 单个灰度功能的放量口径。
     *
     * @param code            灰度代号
     * @param label           中文名称
     * @param percent         当前比例（0~100）
     * @param whitelistSize   白名单人数
     * @param fullyRolledOut  是否全量（比例 100）
     * @param closed          是否已关闭（比例 0，含回滚后）
     */
    public record Feature(String code, String label, int percent, int whitelistSize,
                          boolean fullyRolledOut, boolean closed) {
    }

    /**
     * 熔断配置。
     *
     * @param enabled             是否启用自动熔断
     * @param windowMinutes       观测窗口（分钟）
     * @param minSamples          最小样本轮次数
     * @param errorRatePercent    错误率阈值（百分比）
     * @param p95Ms               P95 时延阈值（0 表示不启用）
     * @param checkIntervalSecond 评估间隔（秒），FR-22 AC② 要求 60s 内回滚
     */
    public record Breaker(boolean enabled, int windowMinutes, int minSamples,
                          int errorRatePercent, int p95Ms, long checkIntervalSecond) {
    }

    /**
     * 近窗健康度。
     *
     * @param windowMinutes    窗口（分钟）
     * @param turns            链路轮次
     * @param anomalies        L2~L4 异常数
     * @param errorRatePercent 错误率（百分比）
     * @param p95LatencyMs     P95 时延（ms）
     * @param exceededBudget   超预算链路数（仅观测）
     * @param sampledAt        取样时间
     */
    public record Health(int windowMinutes, long turns, long anomalies,
                         BigDecimal errorRatePercent, int p95LatencyMs, long exceededBudget,
                         LocalDateTime sampledAt) {
    }
}
