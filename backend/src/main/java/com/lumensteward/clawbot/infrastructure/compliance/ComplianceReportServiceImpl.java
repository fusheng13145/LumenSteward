package com.lumensteward.clawbot.infrastructure.compliance;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumensteward.clawbot.application.compliance.ComplianceReport;
import com.lumensteward.clawbot.application.compliance.ComplianceReportService;
import com.lumensteward.clawbot.application.retention.DataRetentionService;
import com.lumensteward.clawbot.infrastructure.persistence.entity.AuditLogEntity;
import com.lumensteward.clawbot.infrastructure.persistence.entity.OrchestrationTraceEntity;
import com.lumensteward.clawbot.infrastructure.persistence.entity.RateLimitLogEntity;
import com.lumensteward.clawbot.infrastructure.persistence.entity.ToolCallLogEntity;
import com.lumensteward.clawbot.infrastructure.persistence.entity.WxUserEntity;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.AuditLogMapper;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.MemoryItemMapper;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.OrchestrationTraceMapper;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.PetProfileMapper;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.RateLimitLogMapper;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.ToolCallLogMapper;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.WxMessageMapper;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.WxSessionMapper;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.WxUserMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * {@link ComplianceReportService} 实现（B-5 / W4）。
 *
 * <p>纯内部只读聚合：分别调用各表 Mapper 的 {@code selectCount}/{@code selectList}/
 * {@code selectPage} 统计当前基数与分布。保留天数取自
 * {@link DataRetentionService} 的接口常量（硬编码、非配置驱动，故报告即反映有效值）。
 * 定时清理（{@code DataRetentionServiceImpl.purgeAll}）仅记录 INFO 日志、不落执行表，
 * 故报告呈现「策略 + 下次执行(cron) + 当前基数」，而非历史执行日志。
 */
@Service
public class ComplianceReportServiceImpl implements ComplianceReportService {

    private static final Logger log = LoggerFactory.getLogger(ComplianceReportServiceImpl.class);

    /** 数据删除审计动作标识（{@code log_audit.action}）。 */
    private static final String ACTION_DATA_DELETE = "DATA_DELETE";

    /** 匿名化 openid 前缀（{@code UserDataDeletionServiceImpl.anonymize} 生成的 anon_<16hex>）。 */
    private static final String ANON_PREFIX = "anon_";

    /** 单次报告展示的最近审计条数。 */
    private static final int RECENT_AUDIT_LIMIT = 10;

    /** 系统标识（用于报告抬头与自证）。 */
    private static final String SYSTEM_IDENTIFIER = "衔光管家 (LumenSteward / Clawbot)";

    private final AuditLogMapper auditLogMapper;
    private final ToolCallLogMapper toolLogMapper;
    private final RateLimitLogMapper rateLimitLogMapper;
    private final OrchestrationTraceMapper orchestrationTraceMapper;
    private final WxMessageMapper wxMessageMapper;
    private final WxSessionMapper wxSessionMapper;
    private final PetProfileMapper petProfileMapper;
    private final MemoryItemMapper memoryItemMapper;
    private final WxUserMapper wxUserMapper;

    /**
     * 构造器注入（G-14，单构造器由 Spring 自动装配）。
     *
     * @param auditLogMapper           审计 Mapper
     * @param toolLogMapper            工具调用 Mapper
     * @param rateLimitLogMapper       限流 Mapper
     * @param orchestrationTraceMapper 编排链路 Mapper
     * @param wxMessageMapper          消息 Mapper
     * @param wxSessionMapper          会话 Mapper
     * @param petProfileMapper         宠物档案 Mapper
     * @param memoryItemMapper         个人状态库 Mapper（W6 新增 PII 载体，须出现在基数中）
     * @param wxUserMapper             用户 Mapper
     */
    public ComplianceReportServiceImpl(AuditLogMapper auditLogMapper,
                                      ToolCallLogMapper toolLogMapper,
                                      RateLimitLogMapper rateLimitLogMapper,
                                      OrchestrationTraceMapper orchestrationTraceMapper,
                                      WxMessageMapper wxMessageMapper,
                                      WxSessionMapper wxSessionMapper,
                                      PetProfileMapper petProfileMapper,
                                      MemoryItemMapper memoryItemMapper,
                                      WxUserMapper wxUserMapper) {
        this.auditLogMapper = auditLogMapper;
        this.toolLogMapper = toolLogMapper;
        this.rateLimitLogMapper = rateLimitLogMapper;
        this.orchestrationTraceMapper = orchestrationTraceMapper;
        this.wxMessageMapper = wxMessageMapper;
        this.wxSessionMapper = wxSessionMapper;
        this.petProfileMapper = petProfileMapper;
        this.memoryItemMapper = memoryItemMapper;
        this.wxUserMapper = wxUserMapper;
    }

    @Override
    public ComplianceReport generateReport() {
        log.info("生成合规报告（聚合留存 / 删除执行证据）");
        return new ComplianceReport(
                LocalDateTime.now(),
                SYSTEM_IDENTIFIER,
                buildRetention(),
                buildDeletion(),
                buildAudit(),
                buildToolCall(),
                buildRateLimit(),
                buildOrchestration(),
                buildStatement());
    }

    /** 一、数据保留策略与执行（FR-19 ①）。 */
    private ComplianceReport.RetentionPolicySection buildRetention() {
        return new ComplianceReport.RetentionPolicySection(
                DataRetentionService.MESSAGE_RETENTION_DAYS,
                DataRetentionService.TOOL_LOG_RETENTION_DAYS,
                DataRetentionService.PET_SOFT_DELETE_GRACE_DAYS,
                DataRetentionService.MEMORY_HISTORY_RETENTION_DAYS,
                "0 0 3 * * ?",
                "每日 03:00 由 DataRetentionServiceImpl.purgeAll() 执行；仅记录 INFO 日志，不持久化执行表",
                count(wxMessageMapper),
                count(wxSessionMapper),
                count(toolLogMapper),
                count(petProfileMapper),
                count(memoryItemMapper));
    }

    /** 二、删除与匿名化执行（FR-19 ②）。 */
    private ComplianceReport.DeletionSection buildDeletion() {
        long totalDataDelete = countWhere(auditLogMapper,
                w -> w.eq(AuditLogEntity::getAction, ACTION_DATA_DELETE));
        List<AuditLogEntity> scopeRows = auditLogMapper.selectList(
                new LambdaQueryWrapper<AuditLogEntity>()
                        .eq(AuditLogEntity::getAction, ACTION_DATA_DELETE)
                        .select(AuditLogEntity::getRegType));
        Map<String, Long> byScope = tally(scopeRows, e -> e.getRegType());

        long anonymizedToolLogs = countWhere(toolLogMapper,
                w -> w.likeRight(ToolCallLogEntity::getOpenid, ANON_PREFIX));
        long anonymizedUserAnchors = countWhere(wxUserMapper,
                w -> w.likeRight(WxUserEntity::getOpenid, ANON_PREFIX));
        return new ComplianceReport.DeletionSection(totalDataDelete, byScope,
                anonymizedToolLogs, anonymizedUserAnchors);
    }

    /** 三、审计与运维。 */
    private ComplianceReport.AuditSection buildAudit() {
        long total = count(auditLogMapper);
        List<AuditLogEntity> actionRows = auditLogMapper.selectList(
                new LambdaQueryWrapper<AuditLogEntity>().select(AuditLogEntity::getAction));
        Map<String, Long> byAction = tally(actionRows, AuditLogEntity::getAction);

        Page<AuditLogEntity> recentPage = auditLogMapper.selectPage(
                new Page<>(1, RECENT_AUDIT_LIMIT),
                new LambdaQueryWrapper<AuditLogEntity>().orderByDesc(AuditLogEntity::getCreatedAt));
        List<ComplianceReport.RecentAuditRow> recent = recentPage.getRecords().stream()
                .map(e -> new ComplianceReport.RecentAuditRow(
                        e.getId(), e.getRegType(), e.getAction(), e.getTarget(),
                        e.getReason(), e.getResult(), e.getCreatedAt()))
                .toList();
        return new ComplianceReport.AuditSection(total, byAction, recent);
    }

    /** 四、工具调用与异常。 */
    private ComplianceReport.ToolCallSection buildToolCall() {
        long total = count(toolLogMapper);
        List<ToolCallLogEntity> errorRows = toolLogMapper.selectList(
                new LambdaQueryWrapper<ToolCallLogEntity>().select(ToolCallLogEntity::getErrorType));
        Map<String, Long> byErrorType = tally(errorRows, ToolCallLogEntity::getErrorType);

        List<ToolCallLogEntity> statusRows = toolLogMapper.selectList(
                new LambdaQueryWrapper<ToolCallLogEntity>().select(ToolCallLogEntity::getStatus));
        Map<Integer, Long> byStatus = new LinkedHashMap<>();
        long success = 0;
        for (ToolCallLogEntity e : statusRows) {
            Integer status = e.getStatus();
            int key = status == null ? -1 : status;
            byStatus.merge(key, 1L, Long::sum);
            if (status != null && status == 0) {
                success++;
            }
        }
        double failureRate = total == 0 ? 0d : round2((total - success) * 100.0 / total);
        return new ComplianceReport.ToolCallSection(total, byErrorType, byStatus, failureRate);
    }

    /** 五、限流。 */
    private ComplianceReport.RateLimitSection buildRateLimit() {
        long total = count(rateLimitLogMapper);
        List<RateLimitLogEntity> typeRows = rateLimitLogMapper.selectList(
                new LambdaQueryWrapper<RateLimitLogEntity>().select(RateLimitLogEntity::getLimitType));
        Map<String, Long> byLimitType = tally(typeRows, RateLimitLogEntity::getLimitType);
        long ipFreq = byLimitType.getOrDefault("IP_FREQ", 0L);
        double blockRate = total == 0 ? 0d : round2(ipFreq * 100.0 / total);
        return new ComplianceReport.RateLimitSection(total, byLimitType, blockRate);
    }

    /** 六、编排链路。 */
    private ComplianceReport.OrchestrationSection buildOrchestration() {
        long total = count(orchestrationTraceMapper);
        List<OrchestrationTraceEntity> rows = orchestrationTraceMapper.selectList(
                new LambdaQueryWrapper<OrchestrationTraceEntity>()
                        .select(OrchestrationTraceEntity::getExceededBudget,
                                OrchestrationTraceEntity::getTotalMs));
        Map<String, Long> byBudgetExceeded = new LinkedHashMap<>();
        Map<String, Long> byLatencyBucket = new LinkedHashMap<>();
        for (OrchestrationTraceEntity e : rows) {
            boolean exceeded = Boolean.TRUE.equals(e.getExceededBudget());
            byBudgetExceeded.merge(exceeded ? "EXCEEDED_BUDGET" : "WITHIN_BUDGET", 1L, Long::sum);
            int ms = e.getTotalMs() == null ? 0 : e.getTotalMs();
            String bucket = ms < 1000 ? "<1s" : ms < 3000 ? "1-3s" : ms < 5000 ? "3-5s" : ">5s";
            byLatencyBucket.merge(bucket, 1L, Long::sum);
        }
        return new ComplianceReport.OrchestrationSection(total, byBudgetExceeded, byLatencyBucket);
    }

    /** 自动生成的合规声明段（仅说明数据来源，不附加任何新采集）。 */
    private String buildStatement() {
        return "本合规报告由系统自动生成，用于数据留存与删除执行的自证。报告所列全部数据均来源于既有日志与"
                + "业务表（log_audit / log_tool_call / log_rate_limit / log_orchestration_trace / wx_message / "
                + "wx_session / biz_pet_profile / biz_memory_item / wx_user），未引入任何新的数据采集；"
                + "所有 openid 类个人标识已按 BR-21 统一脱敏。";
    }

    // ===== 通用统计辅助 =====

    private long count(BaseMapper<?> mapper) {
        return mapper.selectCount(new LambdaQueryWrapper<>());
    }

    private <T> long countWhere(BaseMapper<T> mapper,
                                java.util.function.Consumer<LambdaQueryWrapper<T>> where) {
        LambdaQueryWrapper<T> wrapper = new LambdaQueryWrapper<>();
        where.accept(wrapper);
        return mapper.selectCount(wrapper);
    }

    private <T> Map<String, Long> tally(List<T> rows, Function<T, String> keyExtractor) {
        Map<String, Long> result = new LinkedHashMap<>();
        if (rows == null) {
            return result;
        }
        for (T row : rows) {
            // 只投影可空列时，MyBatis 会把「整行皆为 NULL」映射成 null 元素（如无 error_type 的成功调用）
            String key = row == null ? null : keyExtractor.apply(row);
            result.merge(key == null ? "(空)" : key, 1L, Long::sum);
        }
        return result;
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
