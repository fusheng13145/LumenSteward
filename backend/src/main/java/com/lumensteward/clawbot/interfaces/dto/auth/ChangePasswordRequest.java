package com.lumensteward.clawbot.interfaces.dto.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 修改密码请求（架构 4.3）。
 *
 * @param oldPassword 原密码
 * @param newPassword 新密码（长度 8~64）
 */
public record ChangePasswordRequest(
        @NotBlank(message = "原密码不能为空") String oldPassword,
        @NotBlank(message = "新密码不能为空")
        @Size(min = 8, max = 64, message = "新密码长度须为 8~64 位") String newPassword) {
}
