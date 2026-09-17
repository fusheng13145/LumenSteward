package com.lumensteward.clawbot.infrastructure.persistence.service;

import com.lumensteward.clawbot.application.orchestrator.model.ToolCallRecord;
import com.lumensteward.clawbot.domain.tool.ToolResult;
import com.lumensteward.clawbot.infrastructure.client.llm.dto.ToolCall;

/**
 * 工具调用日志服务（架构 5.4 / SRS 7.3 关键设计，ADR-003）。
 *
 * <p><b>ADR-003：同步落库。</b>工具的 start/end 均<b>同步</b>写入 {@code log_tool_call}，不引入
 * MQ/异步——以保证"用户收到的每条回复均可被系统内部的工具日志支撑"（FR-09 后置条件）。
 */
public interface ToolCallLogService {

    /**
     * 记录调用开始（生成初始行，状态置 NOT_EXECUTED），返回携带主键的记录。
     *
     * @param traceId   链路标识
     * @param openid    用户
     * @param sessionId 会话
     * @param call      模型发起的工具调用
     * @param round     轮次
     * @param callSeq   本次链路调用序号（从 1 递增，AC-B6/B7）
     * @return 工具调用记录（含主键）
     */
    ToolCallRecord logStart(String traceId, String openid, Long sessionId, ToolCall call, int round, int callSeq);

    /**
     * 记录调用结束（按主键回填状态/结果/耗时）。
     *
     * @param record 调用记录
     * @param result 工具结果
     */
    void logEnd(ToolCallRecord record, ToolResult result);
}
