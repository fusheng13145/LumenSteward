package com.lumensteward.clawbot.application.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumensteward.clawbot.common.api.PageQuery;
import com.lumensteward.clawbot.common.api.PageResult;
import com.lumensteward.clawbot.common.enums.ToolStatus;
import com.lumensteward.clawbot.infrastructure.persistence.entity.ToolCallLogEntity;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.ToolCallLogMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 工具调用日志检索与统计（架构 4.3 / GET /api/tool-logs、/api/tool-logs/{id}、/api/tool-logs/stats）。
 *
 * <p>数据源为 T03 同步落库的 {@code log_tool_call}（ADR-003），是执行一致性校验的同一事实依据；
 * 统计口径全部为 COUNT/AVG 聚合，非估算（AC-E5/E6）。
 */
@Service
public class ToolLogQueryService {

    private final ToolCallLogMapper toolCallLogMapper;

    /**
     * 构造器注入（G-14）。
     *
     * @param toolCallLogMapper 工具日志 Mapper
     */
    public ToolLogQueryService(ToolCallLogMapper toolCallLogMapper) {
        this.toolCallLogMapper = toolCallLogMapper;
    }

    /**
     * 分页检索工具调用日志。
     *
     * @param page      分页参数
     * @param traceId   链路标识（可空）
     * @param toolName  工具名（可空）
     * @param status    状态（可空）
     * @param openid    用户（可空）
     * @param startTime 下界（可空）
     * @param endTime   上界（可空）
     * @return 分页结果
     */
    public PageResult<ToolCallLogEntity> page(PageQuery page, String traceId, String toolName,
                                              Integer status, String openid,
                                              LocalDateTime startTime, LocalDateTime endTime) {
        LambdaQueryWrapper<ToolCallLogEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(notBlank(traceId), ToolCallLogEntity::getTraceId, traceId)
                .eq(notBlank(toolName), ToolCallLogEntity::getToolName, toolName)
                .eq(status != null, ToolCallLogEntity::getStatus, status)
                .eq(notBlank(openid), ToolCallLogEntity::getOpenid, openid)
                .ge(startTime != null, ToolCallLogEntity::getCreatedAt, startTime)
                .le(endTime != null, ToolCallLogEntity::getCreatedAt, endTime)
                .orderByDesc(ToolCallLogEntity::getCreatedAt);
        Page<ToolCallLogEntity> mpPage = toolCallLogMapper.selectPage(page.toPage(), wrapper);
        return PageResult.from(mpPage);
    }

    /**
     * 按主键查询日志详情。
     *
     * @param id 主键
     * @return 日志实体
     */
    public Optional<ToolCallLogEntity> findById(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(toolCallLogMapper.selectById(id));
    }

    /**
     * 统计工具调用（调用量 / 成功率 / 耗时分布）。
     *
     * @param toolName  工具名过滤（可空）
     * @param startTime 下界（可空）
     * @param endTime   上界（可空）
     * @return 统计结果
     */
    public Stats stats(String toolName, LocalDateTime startTime, LocalDateTime endTime) {
        long total = countWhere(toolName, null, startTime, endTime);
        long success = countWhere(toolName, ToolStatus.SUCCESS, startTime, endTime);
        long failed = countWhere(toolName, ToolStatus.FAILED, startTime, endTime);
        long degraded = countWhere(toolName, ToolStatus.DEGRADED, startTime, endTime);
        long timeout = countWhere(toolName, ToolStatus.TIMEOUT, startTime, endTime);
        long notExecuted = countWhere(toolName, ToolStatus.NOT_EXECUTED, startTime, endTime);

        List<StatItem> items = groupByTool(toolName, startTime, endTime);
        double avgLatency = weightedAvgLatency(items, total);
        double successRate = total == 0 ? 0.0 : (double) success / total;

        return new Stats(total, success, failed, degraded, timeout, notExecuted,
                successRate, avgLatency, items);
    }

    private long countWhere(String toolName, ToolStatus status,
                            LocalDateTime startTime, LocalDateTime endTime) {
        LambdaQueryWrapper<ToolCallLogEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(notBlank(toolName), ToolCallLogEntity::getToolName, toolName)
                .eq(status != null, ToolCallLogEntity::getStatus, status == null ? null : status.getCode())
                .ge(startTime != null, ToolCallLogEntity::getCreatedAt, startTime)
                .le(endTime != null, ToolCallLogEntity::getCreatedAt, endTime);
        Long count = toolCallLogMapper.selectCount(wrapper);
        return count == null ? 0L : count;
    }

    private List<StatItem> groupByTool(String toolName, LocalDateTime startTime,
                                       LocalDateTime endTime) {
        QueryWrapper<ToolCallLogEntity> wrapper = new QueryWrapper<>();
        wrapper.select("tool_name",
                        "COUNT(*) AS total",
                        "SUM(CASE WHEN status = 0 THEN 1 ELSE 0 END) AS success",
                        "SUM(CASE WHEN status = 1 THEN 1 ELSE 0 END) AS failed",
                        "SUM(CASE WHEN status = 2 THEN 1 ELSE 0 END) AS degraded",
                        "SUM(CASE WHEN status = 3 THEN 1 ELSE 0 END) AS timeout",
                        "AVG(latency_ms) AS avg_latency")
                .eq(notBlank(toolName), "tool_name", toolName)
                .ge(startTime != null, "created_at", startTime)
                .le(endTime != null, "created_at", endTime)
                .groupBy("tool_name");
        List<Map<String, Object>> rows = toolCallLogMapper.selectMaps(wrapper);
        List<StatItem> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            items.add(new StatItem(
                    row.get("tool_name") == null ? null : String.valueOf(row.get("tool_name")),
                    toLong(row.get("total")),
                    toLong(row.get("success")),
                    toLong(row.get("failed")),
                    toLong(row.get("degraded")),
                    toLong(row.get("timeout")),
                    toDouble(row.get("avg_latency"))));
        }
        return items;
    }

    private static double weightedAvgLatency(List<StatItem> items, long total) {
        if (total == 0 || items.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (StatItem item : items) {
            sum += item.avgLatencyMs() * item.total();
        }
        return sum / total;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static double toDouble(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }

    /**
     * 工具调用统计结果。
     *
     * @param total        总量
     * @param success      成功数
     * @param failed       失败数
     * @param degraded     降级数
     * @param timeout      超时数
     * @param notExecuted  未执行数
     * @param successRate  成功率
     * @param avgLatencyMs 平均耗时
     * @param items        按工具分组明细
     */
    public record Stats(long total, long success, long failed, long degraded, long timeout,
                        long notExecuted, double successRate, double avgLatencyMs,
                        List<StatItem> items) {
    }

    /**
     * 按工具名分组的统计项。
     *
     * @param toolName     工具名
     * @param total        调用量
     * @param success      成功数
     * @param failed       失败数
     * @param degraded     降级数
     * @param timeout      超时数
     * @param avgLatencyMs 平均耗时
     */
    public record StatItem(String toolName, long total, long success, long failed,
                           long degraded, long timeout, double avgLatencyMs) {
    }
}
