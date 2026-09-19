package com.lumensteward.clawbot.interfaces.dto.toollog;

import jakarta.validation.constraints.Size;

/**
 * 工具调用回放请求（A-2 / 迭代 2 T12：POST /api/tool-logs/replay、/{id}/replay）。
 *
 * <p>{@code dryRun} 默认 true：非只读工具不实际执行，避免调试动作改写业务数据。
 *
 * @param traceId 链路标识（{@code /replay} 必填；{@code /{id}/replay} 忽略）
 * @param reply   可选：待复现校验的回复文本（给出即重跑执行一致性校验，AC②）
 * @param dryRun  是否干跑（null 视为 true）
 */
public record ReplayRequest(
        @Size(max = 64, message = "traceId 长度上限 64") String traceId,
        @Size(max = 2000, message = "回复文本长度上限 2000") String reply,
        Boolean dryRun) {

    /**
     * 干跑判定（缺省为 true，保守）。
     *
     * @return 是否干跑
     */
    public boolean dryRunOrDefault() {
        return dryRun == null || dryRun;
    }
}
