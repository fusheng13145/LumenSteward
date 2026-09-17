package com.lumensteward.clawbot.interfaces.dto.auth;

import java.util.List;

/**
 * 当前用户与角色（架构 4.3 / GET /api/auth/info）。
 *
 * @param username    登录名
 * @param role        角色
 * @param displayName 展示名
 * @param permissions 权限码列表（与前端 config/permissions.ts 同源）
 */
public record AuthInfoVO(String username,
                         String role,
                         String displayName,
                         List<String> permissions) {
}
