package com.lumensteward.clawbot.interfaces.dto.user;

import jakarta.validation.constraints.Size;

/**
 * 用户档案维护请求（FR-16 / 迭代 2 T11：PUT /api/users/{id}/profile）。
 *
 * <p>昵称置空表示清空；变更前后值写入 {@code log_audit}（FR-16 AC③）。
 *
 * @param nickname 昵称（可空，上限 64）
 * @param reason   变更原因（可空，建议填写以便审计追溯）
 */
public record UserProfileUpdateRequest(
        @Size(max = 64, message = "昵称长度上限 64") String nickname,
        @Size(max = 200, message = "变更原因长度上限 200") String reason) {
}
