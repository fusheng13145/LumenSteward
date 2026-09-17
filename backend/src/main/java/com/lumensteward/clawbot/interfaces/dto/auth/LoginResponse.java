package com.lumensteward.clawbot.interfaces.dto.auth;

/**
 * 登录响应（架构 4.3）。
 *
 * @param token       JWT 令牌
 * @param tokenType   令牌类型，固定 Bearer
 * @param expiresIn   有效期（秒）
 * @param role        角色
 * @param displayName 展示名
 */
public record LoginResponse(String token,
                            String tokenType,
                            long expiresIn,
                            String role,
                            String displayName) {
}
