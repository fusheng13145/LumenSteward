package com.lumensteward.clawbot.application.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lumensteward.clawbot.common.enums.ToolStatus;
import com.lumensteward.clawbot.infrastructure.persistence.entity.ToolCallLogEntity;
import com.lumensteward.clawbot.infrastructure.persistence.entity.WxMessageEntity;
import com.lumensteward.clawbot.infrastructure.persistence.entity.WxUserEntity;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.ToolCallLogMapper;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.WxMessageMapper;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.WxUserMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 概览看板聚合（架构 4.3 / GET /api/dashboard/summary，AC-E5）。
 *
 * <p>全部指标由 COUNT 聚合查询直接产出，与等价 SQL 一一对应，<b>非估算值</b>：
 * <pre>
 *   今日消息量   SELECT COUNT(*) FROM wx_message  WHERE created_at &gt;= 今日 00:00
 *   活跃用户数   SELECT COUNT(*) FROM wx_user     WHERE last_interact_at &gt;= 今日 00:00
 *   工具调用量   SELECT COUNT(*) FROM log_tool_call
 *   成功率       COUNT(status=0) / COUNT(*)
 *   降级次数     COUNT(status=2)
 * </pre>
 */
@Service
public class DashboardService {

    private final WxMessageMapper wxMessageMapper;
    private final WxUserMapper wxUserMapper;
    private final ToolCallLogMapper toolCallLogMapper;

    /**
     * 构造器注入（G-14）。
     *
     * @param wxMessageMapper   消息 Mapper
     * @param wxUserMapper      用户 Mapper
     * @param toolCallLogMapper 工具日志 Mapper
     */
    public DashboardService(WxMessageMapper wxMessageMapper,
                            WxUserMapper wxUserMapper,
                            ToolCallLogMapper toolCallLogMapper) {
        this.wxMessageMapper = wxMessageMapper;
        this.wxUserMapper = wxUserMapper;
        this.toolCallLogMapper = toolCallLogMapper;
    }

    /**
     * 生成看板汇总。
     *
     * @return 汇总数据
     */
    public Summary summary() {
        LocalDateTime todayStart = LocalDate.now().atStartOfDay();

        long todayMessages = count(wxMessageMapper.selectCount(
                new LambdaQueryWrapper<WxMessageEntity>()
                        .ge(WxMessageEntity::getCreatedAt, todayStart)));

        long activeUsers = count(wxUserMapper.selectCount(
                new LambdaQueryWrapper<WxUserEntity>()
                        .ge(WxUserEntity::getLastInteractAt, todayStart)));

        long toolCalls = count(toolCallLogMapper.selectCount(null));
        long toolSuccess = countByStatus(ToolStatus.SUCCESS);
        long toolFailed = countByStatus(ToolStatus.FAILED);
        long toolDegraded = countByStatus(ToolStatus.DEGRADED);
        long toolTimeout = countByStatus(ToolStatus.TIMEOUT);

        double successRate = toolCalls == 0 ? 0.0 : (double) toolSuccess / toolCalls;

        return new Summary(todayMessages, activeUsers, toolCalls, toolSuccess, toolFailed,
                toolDegraded, toolTimeout, successRate, toolDegraded);
    }

    private long countByStatus(ToolStatus status) {
        return count(toolCallLogMapper.selectCount(new LambdaQueryWrapper<ToolCallLogEntity>()
                .eq(ToolCallLogEntity::getStatus, status.getCode())));
    }

    private static long count(Long value) {
        return value == null ? 0L : value;
    }

    /**
     * 看板汇总值（application 内部模型，避免对外层 DTO 的向上依赖）。
     *
     * @param todayMessages 今日消息量
     * @param activeUsers   活跃用户数
     * @param toolCalls     工具调用量
     * @param toolSuccess   成功数
     * @param toolFailed    失败数
     * @param toolDegraded  降级数
     * @param toolTimeout   超时数
     * @param successRate   成功率
     * @param degradedCount 降级次数
     */
    public record Summary(long todayMessages,
                          long activeUsers,
                          long toolCalls,
                          long toolSuccess,
                          long toolFailed,
                          long toolDegraded,
                          long toolTimeout,
                          double successRate,
                          long degradedCount) {
    }
}
