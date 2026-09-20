package com.lumensteward.clawbot.domain.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务型多步会话的上下文（SRS FR-24 / 架构 4.3 状态机）。
 *
 * <p><b>与 LLM 上下文分离存储（BR-34）：</b>本对象仅在追问（缺必填参数）产生时建立，
 * 落 {@code wx_session.task_context} JSON 列并镜像到 Redis {@code task:{openid}}（TTL 10 分钟）；
 * 它与 {@code conv:{openid}} 的对话历史互不影响，避免把"任务状态"混入模型上下文而污染推理。
 *
 * <p><b>槽位命名约定：</b>{@code requiredSlots}/{@code filledSlots} 的键与工具 JSON-Schema 的
 * {@code required} 字段名（{@code snake_case}，如 {@code tracking_no}）保持一致，槽位齐备后可直接
 * 作为工具入参续接执行，无需再做字段映射。
 *
 * @param taskType       任务类型（取触发工具名，如 {@code query_express}）
 * @param requiredSlots  必填槽位（工具 Schema 的 required 字段名）
 * @param filledSlots    已填充槽位（键=槽位名，值=用户补充的原文）
 * @param expireAt       任务过期时刻（无交互超过 TTL 即失效，默认 10 分钟）
 * @param promptCount    已发出的追问次数（含初始追问，从 1 起）
 * @param invalidAttempts 连续无效填充次数（达阈值即放弃，BR-33）
 * @param createdAt      任务建立时刻
 */
public record TaskContext(String taskType,
                          List<String> requiredSlots,
                          Map<String, String> filledSlots,
                          Instant expireAt,
                          int promptCount,
                          int invalidAttempts,
                          Instant createdAt) {

    public TaskContext {
        requiredSlots = requiredSlots == null ? List.of() : List.copyOf(requiredSlots);
        filledSlots = filledSlots == null ? Map.of() : Map.copyOf(filledSlots);
    }

    /**
     * 建立新任务（初始追问已发出，故 {@code promptCount=1}）。
     *
     * @param taskType      任务类型
     * @param requiredSlots 必填槽位
     * @param expireAt      过期时刻
     * @param now           建立时刻
     * @return 新任务上下文
     */
    public static TaskContext newTask(String taskType, List<String> requiredSlots,
                                      Instant expireAt, Instant now) {
        return new TaskContext(taskType, requiredSlots, Map.of(), expireAt, 1, 0, now);
    }

    /** 尚未填充的必填槽位（保持声明顺序）。 */
    public List<String> pendingSlots() {
        List<String> pending = new ArrayList<>();
        for (String slot : requiredSlots) {
            String value = filledSlots.get(slot);
            if (value == null || value.isBlank()) {
                pending.add(slot);
            }
        }
        return pending;
    }

    /** 槽位是否已齐备（可续接执行原任务）。 */
    public boolean complete() {
        return pendingSlots().isEmpty();
    }

    /**
     * 是否已过期。
     *
     * @param now 当前时刻
     * @return 过期返回 true
     */
    public boolean expired(Instant now) {
        return expireAt != null && !now.isBefore(expireAt);
    }

    /**
     * 合并槽位填充结果（成功填充后清零无效计数、刷新过期时刻）。
     *
     * @param fill        本次填充（槽位名 → 值）
     * @param newExpireAt 刷新后的过期时刻
     * @return 新任务上下文
     */
    public TaskContext withFilled(Map<String, String> fill, Instant newExpireAt) {
        Map<String, String> merged = new LinkedHashMap<>(filledSlots);
        if (fill != null) {
            merged.putAll(fill);
        }
        return new TaskContext(taskType, requiredSlots, merged, newExpireAt, promptCount, 0, createdAt);
    }

    /**
     * 记录一次无效填充（追问次数 +1、无效计数 +1、刷新过期时刻）。
     *
     * @param newExpireAt 刷新后的过期时刻
     * @return 新任务上下文
     */
    public TaskContext withInvalidAttempt(Instant newExpireAt) {
        return new TaskContext(taskType, requiredSlots, filledSlots, newExpireAt,
                promptCount + 1, invalidAttempts + 1, createdAt);
    }
}
