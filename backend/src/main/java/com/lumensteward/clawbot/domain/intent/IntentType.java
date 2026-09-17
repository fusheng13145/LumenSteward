package com.lumensteward.clawbot.domain.intent;

/**
 * 意图类型（架构 5.2 / SRS FR-05）。
 *
 * <p>与前端 {@code src/utils/constants.ts} 的 INTENT 同源。
 */
public enum IntentType {

    /** 宠物档案。 */
    PET_PROFILE,
    /** 快递查询。 */
    EXPRESS,
    /** 导航。 */
    NAVIGATION,
    /** 语音合成。 */
    TTS,
    /** 图片识别。 */
    IMAGE,
    /** 闲聊。 */
    CHITCHAT,
    /** 未知（低置信度 → 追问）。 */
    UNKNOWN
}
