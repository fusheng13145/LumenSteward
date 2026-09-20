package com.lumensteward.clawbot.application.compliance;

/**
 * 合规报告服务（B-5 / W4 合规报告自动化）。
 *
 * <p>聚合既有<b>留存 / 删除执行</b>相关数据，生成结构化 {@link ComplianceReport}，
 * 供接口层装配为 Markdown 导出。本服务只读既有日志 / 业务表，不写入任何新表，
 * 不引入新的数据采集（FR-19 ② 留痕已落 {@code log_audit}）。
 */
public interface ComplianceReportService {

    /**
     * 生成合规报告聚合。
     *
     * @return 结构化合规报告
     */
    ComplianceReport generateReport();
}
