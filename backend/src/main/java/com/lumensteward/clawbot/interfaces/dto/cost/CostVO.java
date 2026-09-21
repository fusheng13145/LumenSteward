package com.lumensteward.clawbot.interfaces.dto.cost;

import java.time.LocalDateTime;

/**
 * 成本与配额治理看板的出参契约（B-4 / 迭代 4 W5）。
 *
 * <p>字段与 {@code CostService} 的聚合记录一一对应，只做出参固化、不含业务规则；
 * 数值全部来自 {@code log_llm_call} 明细聚合，可用等价 SQL 复算（FR-17 AC①）。
 * 嵌套形式与 {@code DegradeMetricsVO.AnomalyLayerVO} 同范式，避免六个近空文件。
 */
public final class CostVO {

    private CostVO() {
        // 仅作命名空间，不可实例化
    }

    /**
     * 概览 KPI。
     *
     * @param todayCalls            今日调用次数（DB 明细）
     * @param todayPromptTokens     今日输入 token
     * @param todayCompletionTokens 今日输出 token
     * @param todayTokens           今日合计 token
     * @param dailyBudget           当前生效日预算（与降级判定同源）
     * @param usedPercent           已用百分比（DB 明细口径，0–100）
     * @param status                预算状态（NORMAL / WARN / DEGRADED，按 DB 明细判定）
     * @param counterUsagePercent   已用百分比（Redis 计数桶口径，降级判定实际所用）
     * @param degraded              是否已进入降级（按今日计数桶 / 当前生效预算实时判定，即编排器实际行为）
     * @param monthTokens           本月累计 token
     */
    public record OverviewVO(long todayCalls,
                             long todayPromptTokens,
                             long todayCompletionTokens,
                             long todayTokens,
                             long dailyBudget,
                             int usedPercent,
                             String status,
                             int counterUsagePercent,
                             boolean degraded,
                             long monthTokens) {
    }

    /**
     * 趋势点。
     *
     * @param bucket           时间桶（{@code yyyy-MM-dd} 或 {@code yyyy-MM-dd HH:00}）
     * @param calls            调用次数
     * @param promptTokens     输入 token
     * @param completionTokens 输出 token
     * @param tokens           合计 token
     */
    public record TrendPointVO(String bucket,
                               long calls,
                               long promptTokens,
                               long completionTokens,
                               long tokens) {
    }

    /**
     * 用途分布切片。
     *
     * @param purpose 用途（CHAT / MEMORY_EXTRACT / INTENT）
     * @param calls   调用次数
     * @param tokens  合计 token
     * @param percent 占区间总 token 的百分比（0–100，一位小数）
     */
    public record PurposeSliceVO(String purpose, long calls, long tokens, double percent) {
    }

    /**
     * 模型分布切片。
     *
     * @param provider 供应商
     * @param model    模型名（null 表示请求未指定、使用供应商默认模型）
     * @param calls    调用次数
     * @param tokens   合计 token
     * @param percent  占比
     */
    public record ModelSliceVO(String provider, String model, long calls, long tokens, double percent) {
    }

    /**
     * 用户消耗排行项。
     *
     * @param openid 脱敏后的用户标识（BR-21；无归属的调用聚为「(未归属)」）
     * @param calls  调用次数
     * @param tokens 合计 token
     */
    public record UserSliceVO(String openid, long calls, long tokens) {
    }

    /**
     * 明细行（抽样取证用）。
     *
     * @param id               主键
     * @param purpose          用途
     * @param provider         供应商
     * @param model            模型名（可空）
     * @param openid           脱敏用户标识（可空）
     * @param sessionId        会话 id（可空）
     * @param traceId          链路标识（可空）
     * @param promptTokens     输入 token
     * @param completionTokens 输出 token
     * @param totalTokens      合计 token
     * @param createdAt        调用时间
     */
    public record CallDetailVO(Long id,
                               String purpose,
                               String provider,
                               String model,
                               String openid,
                               Long sessionId,
                               String traceId,
                               int promptTokens,
                               int completionTokens,
                               int totalTokens,
                               LocalDateTime createdAt) {
    }
}
