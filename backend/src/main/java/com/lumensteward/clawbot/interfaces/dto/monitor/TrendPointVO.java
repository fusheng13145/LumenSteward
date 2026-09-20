package com.lumensteward.clawbot.interfaces.dto.monitor;

/**
 * 工具调用趋势点（FR-17 ④：趋势折线图 / T4）。
 *
 * @param bucket      时间桶（日粒度 {@code YYYY-MM-DD}；时粒度 {@code YYYY-MM-DD HH:00}）
 * @param total       该桶调用总量
 * @param success     该桶成功数（status=0）
 * @param successRate 该桶成功率（0~1，总量为 0 时记 0）
 */
public record TrendPointVO(String bucket,
                           long total,
                           long success,
                           double successRate) {
}
