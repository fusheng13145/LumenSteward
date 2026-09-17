package com.lumensteward.clawbot.interfaces.dto.toollog;

import java.util.List;

/**
 * 工具调用统计（架构 4.3 / GET /api/tool-logs/stats）。
 *
 * <p>全部数值由 {@code log_tool_call} 的聚合查询直接产出，<b>非估算</b>（AC-E5 可 SQL 复核）。
 *
 * @param total      调用总量
 * @param success    成功数（status=0）
 * @param failed     失败数（status=1）
 * @param degraded   降级数（status=2）
 * @param timeout    超时数（status=3）
 * @param notExecuted 未执行数（status=4）
 * @param successRate 成功率（0~1，总量为 0 时记 0）
 * @param avgLatencyMs 平均耗时（ms）
 * @param items      按工具名分组的明细
 */
public record ToolStatsVO(long total,
                          long success,
                          long failed,
                          long degraded,
                          long timeout,
                          long notExecuted,
                          double successRate,
                          double avgLatencyMs,
                          List<ToolStatItem> items) {

    /**
     * 按工具名分组的统计项。
     *
     * @param toolName     工具名
     * @param total        调用量
     * @param success      成功数
     * @param failed       失败数
     * @param degraded     降级数
     * @param timeout      超时数
     * @param avgLatencyMs 平均耗时（ms）
     */
    public record ToolStatItem(String toolName,
                               long total,
                               long success,
                               long failed,
                               long degraded,
                               long timeout,
                               double avgLatencyMs) {
    }
}
