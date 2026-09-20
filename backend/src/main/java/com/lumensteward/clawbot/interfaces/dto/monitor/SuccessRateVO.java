package com.lumensteward.clawbot.interfaces.dto.monitor;

/**
 * 按工具名分组的成功率（FR-17 ④：成功率柱状图 / T4）。
 *
 * @param toolName    工具名
 * @param total       调用量
 * @param success     成功数（status=0）
 * @param successRate 成功率（0~1，总量为 0 时记 0）
 */
public record SuccessRateVO(String toolName,
                            long total,
                            long success,
                            double successRate) {
}
