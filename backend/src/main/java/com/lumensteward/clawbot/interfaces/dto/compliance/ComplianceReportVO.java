package com.lumensteward.clawbot.interfaces.dto.compliance;

import com.lumensteward.clawbot.application.compliance.ComplianceReport;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 合规报告视图对象（B-5 / W4，接口层 DTO）。
 *
 * <p>与 {@link ComplianceReport} 同构的结构化对象，作为装配层（{@code ComplianceReportAssembler}）
 * 渲染 Markdown 的输入。字段类型复用领域聚合的不可变记录，避免在接口边界重新定义一遍结构；
 * openid 类个人标识已在装配渲染时经 {@code MaskUtils.openid} 兜底脱敏（BR-21）。
 *
 * @param generatedAt            报告生成时间
 * @param systemIdentifier       系统标识
 * @param retention              数据保留策略与执行（FR-19 ①）
 * @param deletion               删除与匿名化执行（FR-19 ②）
 * @param audit                  审计与运维
 * @param toolCall               工具调用与异常
 * @param rateLimit              限流
 * @param orchestration          编排链路
 * @param statement              自动生成的合规声明段
 */
public record ComplianceReportVO(
        LocalDateTime generatedAt,
        String systemIdentifier,
        ComplianceReport.RetentionPolicySection retention,
        ComplianceReport.DeletionSection deletion,
        ComplianceReport.AuditSection audit,
        ComplianceReport.ToolCallSection toolCall,
        ComplianceReport.RateLimitSection rateLimit,
        ComplianceReport.OrchestrationSection orchestration,
        String statement
) {
}
