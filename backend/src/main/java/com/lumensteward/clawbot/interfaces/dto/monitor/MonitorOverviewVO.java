package com.lumensteward.clawbot.interfaces.dto.monitor;

/**
 * 监控概览 KPI（FR-17 ① / T4）。
 *
 * <p>数值口径与 {@code /api/dashboard/summary} 同源（COUNT 聚合，非估算，AC-E5），
 * 额外补充<b>平均耗时</b>（{@code AVG(latency_ms)}），供监控页概览卡片直读。
 *
 * @param todayMessages  今日消息量（{@code wx_message.created_at >= 今日 00:00}）
 * @param activeUsers    活跃用户数（{@code wx_user.last_interact_at >= 今日 00:00}）
 * @param toolCalls      工具调用总量
 * @param successRate    工具成功率（0~1，总量为 0 时记 0）
 * @param avgLatencyMs   工具平均耗时（ms）
 * @param degradedCount  降级次数（status=2）
 */
public record MonitorOverviewVO(long todayMessages,
                                long activeUsers,
                                long toolCalls,
                                double successRate,
                                double avgLatencyMs,
                                long degradedCount) {
}
