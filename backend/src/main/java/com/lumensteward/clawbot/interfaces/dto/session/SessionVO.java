package com.lumensteward.clawbot.interfaces.dto.session;

import java.time.LocalDateTime;

/**
 * 会话视图（架构 4.3 / GET /api/sessions）。
 *
 * @param id           主键
 * @param openid       脱敏后的 openid
 * @param contextKey   Redis 上下文键（conv:{openid}）
 * @param state        会话状态（IDLE/CHATTING/TASKING/DEGRADED）
 * @param turnCount    累计轮次
 * @param lastActiveAt 最后活跃时间
 * @param createdAt    创建时间
 */
public record SessionVO(Long id,
                        String openid,
                        String contextKey,
                        String state,
                        Integer turnCount,
                        LocalDateTime lastActiveAt,
                        LocalDateTime createdAt) {
}
