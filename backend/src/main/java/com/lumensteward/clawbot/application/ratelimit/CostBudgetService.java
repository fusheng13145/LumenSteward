package com.lumensteward.clawbot.application.ratelimit;

import com.lumensteward.clawbot.common.enums.LlmCallPurpose;

/**
 * 成本保护与计量服务（FR-20 ③ / B-4）。
 *
 * <p>两条职责共用一次调用点，保证<b>看板明细与预算计数同源</b>（FR-17 AC① 可互相核对）：
 * <ul>
 *   <li>写 {@code log_llm_call} 明细（脱敏在基础设施侧集中完成，BR-21）；</li>
 *   <li>累加 Redis 日/时桶；达日预算 80% 告警、100% 降级为「仅基础回复」模式，
 *       次日零点随日桶 TTL 自动恢复。阈值经 {@code rate_limit.daily_token_budget}
 *       运行时可调（备选流 3a），调高与调低都即时生效。</li>
 * </ul>
 */
public interface CostBudgetService {

    /**
     * 记录一次 LLM 调用：落明细 + 累加预算计数。
     *
     * <p>best-effort：任一子写入失败都只告警，绝不向主链路抛出（成本统计不得反过来影响回复）。
     *
     * @param usage 本次调用（openid 传原始值，脱敏由实现完成；为 null 或用途缺失时忽略）
     */
    void recordLlmCall(LlmCallUsage usage);

    /**
     * 是否处于预算耗尽降级模式。
     *
     * <p>按「今日计数桶 / 当前生效预算」<b>实时</b>判定，不依赖粘滞标志位：运维当天抬高预算后
     * 应立即退出降级，反之调低预算应立即进入（Redis 不可用时按未降级处理，放行对话）。
     *
     * @return true 表示已达日预算上限，编排器应返回基础回复
     */
    boolean isDegraded();

    /**
     * 当日预算消耗百分比（0–100，预算为 0 时返回 0）。
     *
     * <p>口径为 <b>Redis 计数桶</b>；与看板的 DB 聚合口径同源，但 Redis 被清空后会归零，
     * 此时以 DB 明细为准（差异须在手册中如实声明）。
     *
     * @return 百分比
     */
    int dailyUsagePercent();

    /**
     * 当前生效的日 token 预算（FR-18 动态值，未配置则回退代码默认）。
     *
     * <p>看板的百分比分母必须与降级判定用的是<b>同一个</b>预算值，否则会出现
     * 「进度条未满 100% 却已降级」。
     *
     * @return 日预算 token 数
     */
    long dailyTokenBudget();

    /**
     * 单次 LLM 调用的计量入参。
     *
     * <p>{@code totalTokens} 由 prompt+completion 推导，<b>不采用 provider 回报的 total</b>：
     * 明细与日预算必须同算法，否则两处数字无法互相核对。
     *
     * @param purpose            调用用途（必填，成本归因的分类键）
     * @param provider           供应商标识（取自 {@code LlmClient.provider()}）
     * @param model              模型名；请求未显式指定供应商默认模型时传 null
     * @param openid             原始 openid；调用点无用户归属时传 null
     * @param sessionId          会话 id（可空）
     * @param traceId            链路标识（可空，缺失时实现回落当前线程 traceId）
     * @param promptTokens       输入 token
     * @param completionTokens   输出 token
     */
    record LlmCallUsage(LlmCallPurpose purpose, String provider, String model, String openid,
                        Long sessionId, String traceId, int promptTokens, int completionTokens) {

        /** 合计 token（与 Redis 日预算计数同一算法）。 */
        public int totalTokens() {
            return promptTokens + completionTokens;
        }
    }
}
