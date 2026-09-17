package com.lumensteward.clawbot.interfaces.dto.dashboard;

/**
 * 概览看板汇总（架构 4.3 / GET /api/dashboard/summary）。
 *
 * <p><b>数值口径（AC-E5）：</b>全部由 {@code wx_message}/{@code wx_user}/{@code log_tool_call}
 * 的 COUNT 聚合直接产出，可用等价 SQL 直接复核，<b>不得</b>为估算值。
 *
 * @param todayMessages 今日消息量（{@code wx_message.created_at >= 今日 00:00}）
 * @param activeUsers   活跃用户数（{@code wx_user.last_interact_at >= 今日 00:00}）
 * @param toolCalls     工具调用总量
 * @param toolSuccess   成功数（status=0）
 * @param toolFailed    失败数（status=1）
 * @param toolDegraded  降级数（status=2）
 * @param toolTimeout   超时数（status=3）
 * @param successRate   工具成功率（0~1，总量为 0 时记 0）
 * @param degradedCount 降级次数（= toolDegraded，单列便于看板直读）
 */
public record DashboardSummaryVO(long todayMessages,
                                 long activeUsers,
                                 long toolCalls,
                                 long toolSuccess,
                                 long toolFailed,
                                 long toolDegraded,
                                 long toolTimeout,
                                 double successRate,
                                 long degradedCount) {
}
