package com.lumensteward.clawbot.application.compliance;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 合规报告聚合结果（B-5 / W4 合规报告自动化，FR-19 ①/② 留存与删除执行证据）。
 *
 * <p>本对象是纯内部聚合产物：所有数据均来自既有日志 / 业务表（{@code log_audit} /
 * {@code log_tool_call} / {@code log_rate_limit} / {@code log_orchestration_trace} /
 * {@code wx_message} / {@code wx_session} / {@code biz_pet_profile} / {@code biz_memory_item} /
 * {@code wx_user}），<b>不引入任何新的数据采集</b>。openid 类个人标识在装配为 VO / Markdown 时统一经
 * {@code MaskUtils.openid} 脱敏（BR-21）。
 *
 * <p>结构按六大主题分节，每节为一个不可变记录，便于服务层聚合、接口层装配与渲染。
 */
public record ComplianceReport(

        /** 报告生成时间。 */
        LocalDateTime generatedAt,

        /** 系统标识（用于报告抬头与自证）。 */
        String systemIdentifier,

        /** 一、数据保留策略与执行（FR-19 ①）。 */
        RetentionPolicySection retention,

        /** 二、删除与匿名化执行（FR-19 ②）。 */
        DeletionSection deletion,

        /** 三、审计与运维。 */
        AuditSection audit,

        /** 四、工具调用与异常。 */
        ToolCallSection toolCall,

        /** 五、限流。 */
        RateLimitSection rateLimit,

        /** 六、编排链路。 */
        OrchestrationSection orchestration,

        /** 自动生成的合规声明段。 */
        String statement
) {

    /** 一、数据保留策略与执行（FR-19 ①）。 */
    public record RetentionPolicySection(
            /** 会话消息保留天数（取自 {@code DataRetentionService.MESSAGE_RETENTION_DAYS}）。 */
            int messageRetentionDays,
            /** 工具日志保留天数（取自 {@code DataRetentionService.TOOL_LOG_RETENTION_DAYS}）。 */
            int toolLogRetentionDays,
            /** 软删档案物理清除宽限期（取自 {@code DataRetentionService.PET_SOFT_DELETE_GRACE_DAYS}）。 */
            int petSoftDeleteGraceDays,
            /** 状态库已覆盖历史的保留天数（取自 {@code DataRetentionService.MEMORY_HISTORY_RETENTION_DAYS}）。 */
            int memoryHistoryRetentionDays,
            /** 定时清理 cron 表达式。 */
            String nextRunCron,
            /** 定时清理计划描述。 */
            String nextRunDescription,
            /** {@code wx_message} 当前行数（保留基数）。 */
            long wxMessageCount,
            /** {@code wx_session} 当前行数（保留基数）。 */
            long wxSessionCount,
            /** {@code log_tool_call} 当前行数（保留基数）。 */
            long toolLogCount,
            /** {@code biz_pet_profile} 当前行数（保留基数）。 */
            long petProfileCount,
            /** {@code biz_memory_item} 当前行数（含生效与已覆盖历史，W6 新增 PII 载体）。 */
            long memoryItemCount
    ) {
    }

    /** 二、删除与匿名化执行（FR-19 ②）。 */
    public record DeletionSection(
            /** {@code log_audit} 中 action=DATA_DELETE 的总次数。 */
            long totalDataDelete,
            /** 按范围（ALL/CHAT/PET，即 reg_type）计数。 */
            Map<String, Long> byScope,
            /** 已匿名化工具日志数（openid 以 {@code anon_} 前缀标识）。 */
            long anonymizedToolLogs,
            /** 已匿名化用户锚点数（wx_user.openid 以 {@code anon_} 前缀标识）。 */
            long anonymizedUserAnchors
    ) {
    }

    /** 三、审计与运维。 */
    public record AuditSection(
            /** {@code log_audit} 总行数。 */
            long total,
            /** 按 action 计数。 */
            Map<String, Long> byAction,
            /** 最近若干条审计（仅脱敏后的 target，不含 before/after 原始 PII）。 */
            List<RecentAuditRow> recent
    ) {
    }

    /** 近期审计行（展示字段裁剪，绝不携带 before_value / after_value 原始 PII）。 */
    public record RecentAuditRow(
            Long id,
            String regType,
            String action,
            /** 操作对象（已脱敏，装配时再次兜底脱敏）。 */
            String target,
            String reason,
            Integer result,
            LocalDateTime createdAt
    ) {
    }

    /** 四、工具调用与异常。 */
    public record ToolCallSection(
            /** {@code log_tool_call} 总行数。 */
            long total,
            /** 按异常分类 error_type（L1/L2/L3/L4/(空)）计数。 */
            Map<String, Long> byErrorType,
            /** 按状态 status（0成功 1失败 2降级 3超时 4未执行）计数。 */
            Map<Integer, Long> byStatus,
            /** 失败率百分比（非成功 / 总数，0~100）。 */
            double failureRate
    ) {
    }

    /** 五、限流。 */
    public record RateLimitSection(
            /** {@code log_rate_limit} 总行数。 */
            long total,
            /** 按 limit_type（USER_FREQ / IP_FREQ）计数。 */
            Map<String, Long> byLimitType,
            /** 硬拦截占比百分比（IP_FREQ / 总数，0~100）。 */
            double blockRate
    ) {
    }

    /** 六、编排链路。 */
    public record OrchestrationSection(
            /** {@code log_orchestration_trace} 总行数。 */
            long total,
            /** 按预算是否超限计数（WITHIN_BUDGET / EXCEEDED_BUDGET）。 */
            Map<String, Long> byBudgetExceeded,
            /** 按总耗时分桶计数（<1s / 1-3s / 3-5s / >5s）。 */
            Map<String, Long> byLatencyBucket
    ) {
    }
}
