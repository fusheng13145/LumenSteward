package com.lumensteward.clawbot.interfaces.dto.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * 系统配置批量更新请求（架构 4.3 / PUT /api/configs，BR-25）。
 *
 * <p>MVP 骨架：仅落库 + 审计，<b>不热更新</b>（G-33 如实标注）。变更原因必填（BR-25）。
 *
 * @param reason 变更原因（必填）
 * @param items  待更新项
 */
public record ConfigUpdateRequest(
        @NotBlank(message = "变更原因不能为空") String reason,
        @NotEmpty(message = "更新项不能为空") @Valid List<Item> items) {

    /**
     * 单项更新。
     *
     * @param configKey   键名
     * @param configValue 新值
     */
    public record Item(
            @NotBlank(message = "配置键不能为空") String configKey,
            String configValue) {
    }
}
