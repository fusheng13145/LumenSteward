package com.lumensteward.clawbot.application.safety;

/**
 * 一致性校验结论（架构 5.2 / SRS 9.4.5）。
 *
 * @param passed    是否通过
 * @param claimText 命中问题的声明原文（通过时为空）
 * @param reason    判定原因
 */
public record ConsistencyVerdict(boolean passed, String claimText, ConsistencyReason reason) {

    /** 通过（工厂方法名为 pass，避免与记录组件访问器 passed() 冲突）。 */
    public static ConsistencyVerdict pass() {
        return new ConsistencyVerdict(true, null, ConsistencyReason.PASSED);
    }

    /** 检出（执行性幻觉：声称执行但无成功记录）。 */
    public static ConsistencyVerdict unsupported(String claimText) {
        return new ConsistencyVerdict(false, claimText, ConsistencyReason.CLAIM_UNSUPPORTED);
    }

    /** 检出（事实性幻觉：数值不可回溯）。 */
    public static ConsistencyVerdict notTraceable(String claimText) {
        return new ConsistencyVerdict(false, claimText, ConsistencyReason.VALUE_NOT_TRACEABLE);
    }
}
