package com.lumensteward.clawbot.interfaces.dto.session;

import java.time.LocalDateTime;

/**
 * 消息视图（架构 4.3 / GET /api/sessions/{id}/messages）。
 *
 * @param id         主键
 * @param sessionId  所属会话
 * @param openid     脱敏后的 openid
 * @param msgId      微信 MsgId
 * @param role       角色（user/assistant/tool）
 * @param msgType    消息类型（text/image/voice/location/event）
 * @param content    内容
 * @param toolName   工具名（role=tool 时有值）
 * @param tokenCount 估算 token 数
 * @param sendStatus 发送状态：0-待发 1-成功 2-失败
 * @param createdAt  创建时间
 */
public record MessageVO(Long id,
                        Long sessionId,
                        String openid,
                        String msgId,
                        String role,
                        String msgType,
                        String content,
                        String toolName,
                        Integer tokenCount,
                        Integer sendStatus,
                        LocalDateTime createdAt) {
}
