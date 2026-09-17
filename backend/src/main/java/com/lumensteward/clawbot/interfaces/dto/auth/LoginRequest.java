package com.lumensteward.clawbot.interfaces.dto.auth;

import jakarta.validation.constraints.NotBlank;

/**
 * 登录请求（架构 4.3）。
 *
 * @param username 登录名
 * @param password 密码（明文仅存在于请求体，禁止落库/落日志）
 */
public record LoginRequest(
        @NotBlank(message = "用户名不能为空") String username,
        @NotBlank(message = "密码不能为空") String password) {
}
