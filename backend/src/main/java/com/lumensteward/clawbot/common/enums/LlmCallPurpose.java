package com.lumensteward.clawbot.common.enums;

/**
 * LLM 调用用途（B-4 成本计量归因，迭代 4 W5）。
 *
 * <p>枚举名即落库值（{@code log_llm_call.purpose}）。三类之外不设「其他」兜底：新增调用点
 * 必须显式归类，否则成本会静默流向无法解释的桶（与 {@link AnomalyLayer} 同一纪律）。
 */
public enum LlmCallPurpose {

    /** Agent Loop 对话编排（含 SC-04 强制收敛那次调用）。 */
    CHAT,

    /** 个人状态库生长抽取（W6，链路成功后的额外一次调用）。 */
    MEMORY_EXTRACT,

    /** 意图分类（话题切换判定，FR-05）。 */
    INTENT
}
