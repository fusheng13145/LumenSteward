package com.lumensteward.clawbot.interfaces.assembler;

import com.lumensteward.clawbot.application.compliance.ComplianceReport;
import com.lumensteward.clawbot.common.util.MaskUtils;
import com.lumensteward.clawbot.interfaces.dto.compliance.ComplianceReportVO;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 合规报告装配器（B-5 / W4）。
 *
 * <p>职责（G-11 脱敏纪律）：
 * <ol>
 *   <li>{@link #toVO(ComplianceReport)}：领域聚合 → 接口 DTO；</li>
 *   <li>{@link #renderMarkdown(ComplianceReportVO)}：DTO → Markdown 文本（导出为
 *       {@code text/markdown} 字节流）。</li>
 * </ol>
 *
 * <p>任何 openid 类个人标识在渲染时经 {@link MaskUtils#openid(String)} 兜底脱敏（BR-21）；
 * 近期审计行仅携带脱敏后的 {@code target}，绝不透出 {@code before_value}/{@code after_value} 原始 PII。
 */
@Component
public class ComplianceReportAssembler {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * 领域聚合 → 接口 DTO（同构拷贝，便于接口层独立演进）。
     *
     * @param report 领域聚合
     * @return 接口 DTO
     */
    public ComplianceReportVO toVO(ComplianceReport report) {
        return new ComplianceReportVO(
                report.generatedAt(),
                report.systemIdentifier(),
                report.retention(),
                report.deletion(),
                report.audit(),
                report.toolCall(),
                report.rateLimit(),
                report.orchestration(),
                report.statement());
    }

    /**
     * 渲染 Markdown（B-5 导出正文）。
     *
     * @param vo 接口 DTO
     * @return Markdown 文本
     */
    public String renderMarkdown(ComplianceReportVO vo) {
        StringBuilder md = new StringBuilder();
        md.append("# 衔光管家 合规报告（数据留存与删除执行）\n\n");
        md.append("> 生成时间：")
                .append(vo.generatedAt() == null ? "—" : vo.generatedAt().format(TS)).append('\n');
        md.append("> 系统标识：").append(nullToDash(vo.systemIdentifier())).append("\n\n");

        appendRetention(md, vo);
        appendDeletion(md, vo);
        appendAudit(md, vo);
        appendToolCall(md, vo);
        appendRateLimit(md, vo);
        appendOrchestration(md, vo);
        appendStatement(md, vo);

        return md.toString();
    }

    private void appendRetention(StringBuilder md, ComplianceReportVO vo) {
        md.append("## 一、数据保留策略与执行（FR-19 ①）\n\n");
        ComplianceReport.RetentionPolicySection r = vo.retention();
        md.append("- 消息保留天数：").append(r.messageRetentionDays()).append(" 天\n");
        md.append("- 工具日志保留天数：").append(r.toolLogRetentionDays()).append(" 天\n");
        md.append("- 软删档案物理清除宽限期：").append(r.petSoftDeleteGraceDays()).append(" 天\n");
        md.append("- 状态库已覆盖历史保留天数：").append(r.memoryHistoryRetentionDays()).append(" 天\n");
        md.append("- 定时清理计划：").append(r.nextRunDescription())
                .append("（cron `").append(r.nextRunCron()).append("`）\n");
        md.append("- 当前基数（保留数据源）：\n");
        md.append("  - `wx_message`：").append(r.wxMessageCount()).append(" 行\n");
        md.append("  - `wx_session`：").append(r.wxSessionCount()).append(" 行\n");
        md.append("  - `log_tool_call`：").append(r.toolLogCount()).append(" 行\n");
        md.append("  - `biz_pet_profile`：").append(r.petProfileCount()).append(" 行\n");
        md.append("  - `biz_memory_item`：").append(r.memoryItemCount()).append(" 行（含生效与已覆盖历史）\n\n");
    }

    private void appendDeletion(StringBuilder md, ComplianceReportVO vo) {
        ComplianceReport.DeletionSection d = vo.deletion();
        md.append("## 二、删除与匿名化执行（FR-19 ②）\n\n");
        md.append("- 数据删除(DATA_DELETE)总次数：").append(d.totalDataDelete()).append('\n');
        md.append("- 按范围（reg_type）：");
        appendMapInline(md, d.byScope());
        md.append('\n');
        md.append("- 已匿名化工具日志：").append(d.anonymizedToolLogs()).append(" 条\n");
        md.append("- 已匿名化用户锚点(wx_user)：").append(d.anonymizedUserAnchors()).append(" 个\n\n");
    }

    private void appendAudit(StringBuilder md, ComplianceReportVO vo) {
        ComplianceReport.AuditSection a = vo.audit();
        md.append("## 三、审计与运维\n\n");
        md.append("- 审计日志(`log_audit`)总数：").append(a.total()).append('\n');
        md.append("- 按动作(action)：");
        appendMapInline(md, a.byAction());
        md.append('\n');
        md.append("- 最近 ").append(a.recent() == null ? 0 : a.recent().size())
                .append(" 条（操作对象已脱敏）：\n");
        if (a.recent() != null) {
            for (ComplianceReport.RecentAuditRow row : a.recent()) {
                String target = MaskUtils.openid(row.target());
                md.append("  - [").append(row.id() == null ? "?" : row.id()).append("] ")
                        .append(nullToDash(row.regType())).append('/')
                        .append(nullToDash(row.action())).append(' ')
                        .append(target == null ? "—" : target)
                        .append(" 结果=").append(row.result() == null ? "?" : row.result())
                        .append(" 原因=").append(nullToDash(row.reason())).append('\n');
            }
        }
        md.append('\n');
    }

    private void appendToolCall(StringBuilder md, ComplianceReportVO vo) {
        ComplianceReport.ToolCallSection t = vo.toolCall();
        md.append("## 四、工具调用与异常\n\n");
        md.append("- 工具调用(`log_tool_call`)总数：").append(t.total()).append('\n');
        md.append("- 按异常分类(error_type)：");
        appendMapInline(md, t.byErrorType());
        md.append('\n');
        md.append("- 按状态(status)：");
        if (t.byStatus() != null) {
            for (Map.Entry<Integer, Long> e : t.byStatus().entrySet()) {
                md.append(statusLabel(e.getKey())).append('=').append(e.getValue()).append(' ');
            }
        }
        md.append('\n');
        md.append("- 失败率：").append(percent(t.failureRate())).append('\n');
        md.append("- 说明：status 0成功 1失败 2降级 3超时 4未执行；失败率 = (总数 - 成功) / 总数\n\n");
    }

    private void appendRateLimit(StringBuilder md, ComplianceReportVO vo) {
        ComplianceReport.RateLimitSection r = vo.rateLimit();
        md.append("## 五、限流\n\n");
        md.append("- 限流事件(`log_rate_limit`)总数：").append(r.total()).append('\n');
        md.append("- 按类型(limit_type)：");
        appendMapInline(md, r.byLimitType());
        md.append('\n');
        md.append("- 硬拦截占比(IP_FREQ / 总数)：").append(percent(r.blockRate())).append('\n');
        md.append("- 说明：IP_FREQ 为按来源 IP 硬拦截；本表仅记录命中事件，非命中请求不入库\n\n");
    }

    private void appendOrchestration(StringBuilder md, ComplianceReportVO vo) {
        ComplianceReport.OrchestrationSection o = vo.orchestration();
        md.append("## 六、编排链路\n\n");
        md.append("- 链路追踪(`log_orchestration_trace`)总数：").append(o.total()).append('\n');
        md.append("- 预算是否超限：");
        appendMapInline(md, o.byBudgetExceeded());
        md.append('\n');
        md.append("- 总耗时分布：");
        appendMapInline(md, o.byLatencyBucket());
        md.append('\n');
        md.append("- 说明：耗时分桶 <1s / 1-3s / 3-5s / >5s；预算取自 total_budget_ms / exceeded_budget\n\n");
    }

    private void appendStatement(StringBuilder md, ComplianceReportVO vo) {
        md.append("## 声明\n\n");
        md.append(nullToDash(vo.statement())).append('\n');
    }

    private void appendMapInline(StringBuilder md, Map<String, Long> map) {
        if (map == null || map.isEmpty()) {
            md.append("（无）");
            return;
        }
        for (Map.Entry<String, Long> e : map.entrySet()) {
            md.append(e.getKey()).append('=').append(e.getValue()).append(' ');
        }
    }

    private static String statusLabel(Integer status) {
        return switch (status) {
            case 0 -> "成功";
            case 1 -> "失败";
            case 2 -> "降级";
            case 3 -> "超时";
            case 4 -> "未执行";
            case -1 -> "(空)";
            default -> String.valueOf(status);
        };
    }

    private static String percent(double ratio) {
        return String.format("%.2f%%", ratio);
    }

    private static String nullToDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }
}
