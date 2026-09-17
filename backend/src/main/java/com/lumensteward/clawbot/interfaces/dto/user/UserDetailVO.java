package com.lumensteward.clawbot.interfaces.dto.user;

import java.time.LocalDateTime;

/**
 * 用户详情视图（架构 4.3 / GET /api/users/{id}）。
 *
 * @param id             主键
 * @param openid         脱敏后的 openid
 * @param nickname       昵称
 * @param status         状态：1-正常 0-禁用
 * @param lastInteractAt 最后交互时间
 * @param createdAt      创建时间
 * @param petCount       存活宠物档案数
 * @param sessionCount   会话数
 * @param toolCallCount  工具调用次数
 */
public record UserDetailVO(Long id,
                           String openid,
                           String nickname,
                           Integer status,
                           LocalDateTime lastInteractAt,
                           LocalDateTime createdAt,
                           long petCount,
                           long sessionCount,
                           long toolCallCount) {
}
