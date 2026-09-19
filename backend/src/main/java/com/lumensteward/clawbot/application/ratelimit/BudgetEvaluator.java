package com.lumensteward.clawbot.application.ratelimit;

/**
 * 预算阈值判定（FR-20 ③ 纯函数，便于单测隔离 Redis）。
 *
 * <p>达 80% 进入 {@link BudgetStatus#WARN}，达 100% 进入 {@link BudgetStatus#DEGRADED}。
 */
public final class BudgetEvaluator {

    /** 告警阈值比例（80%）。 */
    public static final double WARN_RATIO = 0.8;
    /** 耗尽阈值比例（100%）。 */
    public static final double DEGRADE_RATIO = 1.0;

    /** 预算状态。 */
    public enum BudgetStatus {
        /** 正常。 */
        NORMAL,
        /** 达 80%，需告警。 */
        WARN,
        /** 达 100%，降级为仅基础回复。 */
        DEGRADED
    }

    private BudgetEvaluator() {
    }

    /**
     * 依据已用量与预算判定状态。
     *
     * @param used   已用 token
     * @param budget 日预算（≤0 视为无上限，返回 NORMAL）
     * @return 状态
     */
    public static BudgetStatus evaluate(long used, long budget) {
        if (budget <= 0) {
            return BudgetStatus.NORMAL;
        }
        double ratio = (double) used / budget;
        if (ratio >= DEGRADE_RATIO) {
            return BudgetStatus.DEGRADED;
        }
        if (ratio >= WARN_RATIO) {
            return BudgetStatus.WARN;
        }
        return BudgetStatus.NORMAL;
    }

    /**
     * 消耗百分比（0–100，预算 ≤0 返回 0）。
     *
     * @param used   已用 token
     * @param budget 日预算
     * @return 百分比
     */
    public static int percent(long used, long budget) {
        if (budget <= 0) {
            return 0;
        }
        return (int) Math.min(100, Math.round((double) used * 100 / budget));
    }
}
