package com.lumensteward.clawbot.interfaces.dto.gray;

import jakarta.validation.constraints.NotBlank;

/**
 * 灰度一键回滚请求（FR-22 / BR-31；POST /api/gray/rollback）。
 *
 * @param reason 回滚原因（必填，BR-25 口径：写入 {@code log_audit.reason}）
 */
public record GrayRollbackRequest(@NotBlank(message = "回滚原因不能为空") String reason) {
}
