package com.lumensteward.clawbot.interfaces.dto.session;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 任务进度视图（SRS FR-24 / GET /api/tasks/{sessionId}）。
 *
 * <p>供管理后台观测处于 {@code TASKING} 的会话：任务类型、槽位填充进度与过期时刻。
 * 与后端 {@code domain.model.TaskContext} 字段同构（额外暴露会话 {@code state}）。
 *
 * @param taskType      任务类型（触发工具名，如 {@code query_express}）；无任务为 null
 * @param requiredSlots 必填槽位
 * @param filledSlots   已填槽位（槽位名 → 值）
 * @param expireAt      任务过期时刻
 * @param state         会话状态（IDLE/CHATTING/TASKING/DEGRADED）
 */
public record TaskContextVO(String taskType,
                            List<String> requiredSlots,
                            Map<String, String> filledSlots,
                            Instant expireAt,
                            String state) {
}
