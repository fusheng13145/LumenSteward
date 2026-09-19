package com.lumensteward.clawbot.application.ratelimit;

/**
 * 成本保护预算服务（FR-20 ③）。
 *
 * <p>按日统计 LLM 调用 token 消耗；达日预算 80% 告警、100% 降级为「仅基础回复」模式，
 * 次日零点自动恢复（标志位 TTL 至午夜）。阈值经 {@code rate_limit.daily_token_budget} 运行时可调（备选流 3a）。
 */
public interface CostBudgetService {

    /**
     * 记录一次 LLM 调用的 token 消耗。
     *
     * @param tokens 本次消耗 token（total）
     */
    void recordLlmCall(int tokens);

    /**
     * 是否处于预算耗尽降级模式。
     *
     * @return true 表示已达日预算上限，编排器应返回基础回复
     */
    boolean isDegraded();

    /**
     * 当日预算消耗百分比（0–100，预算为 0 时返回 0）。
     *
     * @return 百分比
     */
    int dailyUsagePercent();
}
