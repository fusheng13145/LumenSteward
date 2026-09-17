package com.lumensteward.clawbot.application.safety;

/**
 * 一致性判定原因（架构 5.2 / SRS 9.4.5）。
 */
public enum ConsistencyReason {

    /** 通过。 */
    PASSED,
    /** 声称执行但无成功记录（执行性幻觉）。 */
    CLAIM_UNSUPPORTED,
    /** 数值/实体无法回溯至工具结果（事实性幻觉）。 */
    VALUE_NOT_TRACEABLE
}
