package com.lumensteward.clawbot.interfaces.dto.user;

import java.time.LocalDateTime;

/**
 * 用户视图（架构 4.3 / GET /api/users）。
 *
 * <p>出参 {@code openid} 已经 {@code MaskingAssembler} 脱敏（G-11 / BR-21）。
 *
 * @param id             主键
 * @param openid         脱敏后的 openid
 * @param nickname       昵称
 * @param status         状态：1-正常 0-禁用
 * @param lastInteractAt 最后交互时间
 * @param createdAt      创建时间
 */
public record UserVO(Long id,
                     String openid,
                     String nickname,
                     Integer status,
                     LocalDateTime lastInteractAt,
                     LocalDateTime createdAt) {
}
