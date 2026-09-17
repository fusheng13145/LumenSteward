package com.lumensteward.clawbot.interfaces.dto.toollog;

import java.time.LocalDateTime;

/**
 * 工具调用日志视图（架构 4.3 / GET /api/tool-logs）。
 *
 * @param id             主键
 * @param traceId        链路标识
 * @param openid         脱敏后的 openid
 * @param sessionId      会话
 * @param toolName       工具名
 * @param callSeq        调用序号
 * @param status         状态：0-成功 1-失败 2-降级 3-超时 4-未执行
 * @param errorType      异常分类：L1~L4
 * @param fallbackReason 降级原因
 * @param latencyMs      耗时（ms）
 * @param llmRound       Agent Loop 轮次
 * @param createdAt      创建时间
 */
public record ToolLogVO(Long id,
                        String traceId,
                        String openid,
                        Long sessionId,
                        String toolName,
                        Integer callSeq,
                        Integer status,
                        String errorType,
                        String fallbackReason,
                        Integer latencyMs,
                        Integer llmRound,
                        LocalDateTime createdAt) {
}
