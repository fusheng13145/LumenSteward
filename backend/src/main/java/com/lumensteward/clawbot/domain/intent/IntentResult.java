package com.lumensteward.clawbot.domain.intent;

import java.util.Map;

/**
 * 意图识别结果（架构 5.2 / SRS FR-05）。
 *
 * @param intent     意图
 * @param confidence 置信度（[0,1]，&lt; 0.7 触发追问，SRS 2.3.5 L2）
 * @param slots      槽位（如 petName、trackingNo）
 */
public record IntentResult(IntentType intent, double confidence, Map<String, Object> slots) {

    public IntentResult {
        intent = intent == null ? IntentType.UNKNOWN : intent;
        slots = slots == null ? Map.of() : Map.copyOf(slots);
    }

    /** 低置信度（需追问澄清，BR-08）。 */
    public boolean lowConfidence() {
        return confidence < 0.7;
    }

    /** 未知意图结果。 */
    public static IntentResult unknown() {
        return new IntentResult(IntentType.UNKNOWN, 0.0, Map.of());
    }
}
