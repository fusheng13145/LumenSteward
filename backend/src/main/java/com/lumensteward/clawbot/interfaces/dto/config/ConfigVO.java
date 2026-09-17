package com.lumensteward.clawbot.interfaces.dto.config;

import java.time.LocalDateTime;

/**
 * 系统配置视图（架构 4.3 / GET /api/configs）。
 *
 * <p>{@code SECRET} 类型出参仅返回尾号（{@code ****尾4}，G-11 / BR-15）。
 *
 * @param configKey   键名
 * @param configValue 值（SECRET 已脱敏；非密钥明文展示）
 * @param valueType   值类型：STRING/INT/DECIMAL/BOOL/JSON/SECRET
 * @param category    分类：llm/tts/orchestration/security/text/tool/runtime
 * @param description 说明
 * @param encrypted   是否加密存储
 * @param updatedAt   更新时间
 */
public record ConfigVO(String configKey,
                       String configValue,
                       String valueType,
                       String category,
                       String description,
                       boolean encrypted,
                       LocalDateTime updatedAt) {
}
