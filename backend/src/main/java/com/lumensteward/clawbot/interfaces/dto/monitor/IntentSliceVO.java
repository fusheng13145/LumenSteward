package com.lumensteward.clawbot.interfaces.dto.monitor;

/**
 * 意图分布切片（FR-17 ④：意图分布饼图 / T4）。
 *
 * <p>系统未单独持久化 LLM 意图域标签（FR-05 的轻量分类为可选、且不落库），故本图以
 * <b>工具调用归因</b>作为意图域的可用近似口径：按 {@code log_tool_call.tool_name} 归并到
 * 业务意图域（{@code image_recognition}/{@code tts}/{@code express}/{@code navigation}/
 * {@code pet_profile}/{@code chat}），未匹配工具名的调用归入 {@code chat}。
 *
 * @param intent 意图域标签
 * @param count  该意图域调用量
 */
public record IntentSliceVO(String intent,
                            long count) {
}
