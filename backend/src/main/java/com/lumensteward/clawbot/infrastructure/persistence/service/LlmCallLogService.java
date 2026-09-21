package com.lumensteward.clawbot.infrastructure.persistence.service;

import com.lumensteward.clawbot.application.ratelimit.CostBudgetService.LlmCallUsage;

/**
 * LLM 调用计量明细写入服务（B-4 / FR-20 ③，迭代 4 W5）。
 *
 * <p>只做一件事：把一次模型调用写成 {@code log_llm_call} 一行，使 token 消耗从「只在 Redis
 * 计数桶里活到次日零点」变成「可查询、可复核的持久事实」。读侧聚合归 {@code CostService}（只读）。
 */
public interface LlmCallLogService {

    /**
     * 记录一次 LLM 调用（best-effort：失败仅告警，绝不影响主链路）。
     *
     * @param usage 计量入参（openid 传原始值，脱敏在本方法内统一完成）
     */
    void record(LlmCallUsage usage);
}
