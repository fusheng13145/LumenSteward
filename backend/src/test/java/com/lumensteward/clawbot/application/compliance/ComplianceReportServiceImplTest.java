package com.lumensteward.clawbot.application.compliance;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lumensteward.clawbot.common.util.MaskUtils;
import com.lumensteward.clawbot.infrastructure.compliance.ComplianceReportServiceImpl;
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
import com.lumensteward.clawbot.interfaces.assembler.ComplianceReportAssembler;
import com.lumensteward.clawbot.interfaces.dto.compliance.ComplianceReportVO;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 合规报告聚合单测（B-5 / W4）。
 *
 * <p>以 Mockito 桩替代 9 个 Mapper，断言各分节计数正确、删除按范围聚合、匿名化计数被采集，
 * 且渲染出的 Markdown 对 openid 做了脱敏（BR-21）。
 */
class ComplianceReportServiceImplTest {

    private final AuditLogMapper auditLogMapper = mock(AuditLogMapper.class);
    private final ToolCallLogMapper toolLogMapper = mock(ToolCallLogMapper.class);
    private final RateLimitLogMapper rateLimitLogMapper = mock(RateLimitLogMapper.class);
    private final OrchestrationTraceMapper orchestrationTraceMapper = mock(OrchestrationTraceMapper.class);
    private final WxMessageMapper wxMessageMapper = mock(WxMessageMapper.class);
    private final WxSessionMapper wxSessionMapper = mock(WxSessionMapper.class);
    private final PetProfileMapper petProfileMapper = mock(PetProfileMapper.class);
    private final MemoryItemMapper memoryItemMapper = mock(MemoryItemMapper.class);
    private final WxUserMapper wxUserMapper = mock(WxUserMapper.class);

    private final ComplianceReportServiceImpl service = new ComplianceReportServiceImpl(
            auditLogMapper, toolLogMapper, rateLimitLogMapper, orchestrationTraceMapper,
            wxMessageMapper, wxSessionMapper, petProfileMapper, memoryItemMapper, wxUserMapper);
    private final ComplianceReportAssembler assembler = new ComplianceReportAssembler();

    /**
     * MyBatis-Plus 的 Lambda 条件构造需实体的 TableInfo 缓存；
     * 本用例未启动 Spring / MyBatis 上下文，故手工注册被 lambda 引用的实体。
     */
    @BeforeAll
    static void initTableInfoCache() {
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, AuditLogEntity.class);
        TableInfoHelper.initTableInfo(assistant, ToolCallLogEntity.class);
        TableInfoHelper.initTableInfo(assistant, RateLimitLogEntity.class);
        TableInfoHelper.initTableInfo(assistant, OrchestrationTraceEntity.class);
        TableInfoHelper.initTableInfo(assistant, WxUserEntity.class);
    }

    /** 未显式打桩的查询返回空集，避免 Mock 默认 null 导致 NPE。 */
    @BeforeEach
    void stubEmptyDefaults() {
        when(memoryItemMapper.selectCount(any())).thenReturn(0L);
        when(auditLogMapper.selectList(any())).thenReturn(List.of());
        when(auditLogMapper.selectPage(any(), any())).thenReturn(new Page<>(1, 10));
        when(toolLogMapper.selectList(any())).thenReturn(List.of());
        when(rateLimitLogMapper.selectList(any())).thenReturn(List.of());
        when(orchestrationTraceMapper.selectList(any())).thenReturn(List.of());
    }

    @Test
    @DisplayName("各表基数与保留天数被如实聚合")
    void aggregatesBaseCountsAndRetention() {
        when(wxMessageMapper.selectCount(any())).thenReturn(100L);
        when(wxSessionMapper.selectCount(any())).thenReturn(50L);
        when(toolLogMapper.selectCount(any())).thenReturn(30L);
        when(petProfileMapper.selectCount(any())).thenReturn(20L);
        when(memoryItemMapper.selectCount(any())).thenReturn(12L);
        when(auditLogMapper.selectCount(any())).thenReturn(10L);
        when(rateLimitLogMapper.selectCount(any())).thenReturn(4L);
        when(orchestrationTraceMapper.selectCount(any())).thenReturn(2L);
        when(wxUserMapper.selectCount(any())).thenReturn(7L);

        ComplianceReport report = service.generateReport();

        assertThat(report.retention().wxMessageCount()).isEqualTo(100);
        assertThat(report.retention().wxSessionCount()).isEqualTo(50);
        assertThat(report.retention().toolLogCount()).isEqualTo(30);
        assertThat(report.retention().petProfileCount()).isEqualTo(20);
        // W6 新增 PII 载体必须出现在基数中，否则报告低估了系统持有的个人信息
        assertThat(report.retention().memoryItemCount()).isEqualTo(12);
        // 保留天数取自 DataRetentionService 接口常量
        assertThat(report.retention().messageRetentionDays()).isEqualTo(180);
        assertThat(report.retention().toolLogRetentionDays()).isEqualTo(180);
        assertThat(report.retention().petSoftDeleteGraceDays()).isEqualTo(30);
        assertThat(report.retention().memoryHistoryRetentionDays()).isEqualTo(180);
    }

    @Test
    @DisplayName("删除按范围(ALL/CHAT/PET)聚合，匿名化计数被采集")
    void aggregatesDeletionByScopeAndAnonymization() {
        when(wxMessageMapper.selectCount(any())).thenReturn(0L);
        when(wxSessionMapper.selectCount(any())).thenReturn(0L);
        when(toolLogMapper.selectCount(any())).thenReturn(30L);
        when(petProfileMapper.selectCount(any())).thenReturn(0L);
        // DATA_DELETE 计数 = 3（与下方 selectList 返回的 3 行同集合）
        when(auditLogMapper.selectCount(any())).thenReturn(3L);
        when(rateLimitLogMapper.selectCount(any())).thenReturn(0L);
        when(orchestrationTraceMapper.selectCount(any())).thenReturn(0L);
        when(wxUserMapper.selectCount(any())).thenReturn(7L);

        // 删除审计：3 条，分别落在 ALL / CHAT / PET
        AuditLogEntity all = new AuditLogEntity();
        all.setRegType("ALL");
        AuditLogEntity chat = new AuditLogEntity();
        chat.setRegType("CHAT");
        AuditLogEntity pet = new AuditLogEntity();
        pet.setRegType("PET");
        // 顺序：buildDeletion 先 selectList(scope)，随后 buildAudit 再 selectList(action)
        when(auditLogMapper.selectList(any()))
                .thenReturn(List.of(all, chat, pet))
                .thenReturn(List.of());

        ComplianceReport report = service.generateReport();

        assertThat(report.deletion().totalDataDelete()).isEqualTo(3);
        assertThat(report.deletion().byScope()).containsEntry("ALL", 1L)
                .containsEntry("CHAT", 1L).containsEntry("PET", 1L);
        assertThat(report.deletion().anonymizedToolLogs()).isEqualTo(30);
        assertThat(report.deletion().anonymizedUserAnchors()).isEqualTo(7);
    }

    @Test
    @DisplayName("工具调用按 error_type/status 聚合，失败率正确")
    void aggregatesToolCallBreakdownAndFailureRate() {
        when(wxMessageMapper.selectCount(any())).thenReturn(0L);
        when(wxSessionMapper.selectCount(any())).thenReturn(0L);
        when(toolLogMapper.selectCount(any())).thenReturn(4L); // total + anon 均返回 4
        when(petProfileMapper.selectCount(any())).thenReturn(0L);
        when(auditLogMapper.selectCount(any())).thenReturn(0L);
        when(rateLimitLogMapper.selectCount(any())).thenReturn(0L);
        when(orchestrationTraceMapper.selectCount(any())).thenReturn(0L);
        when(wxUserMapper.selectCount(any())).thenReturn(0L);

        ToolCallLogEntity ok = new ToolCallLogEntity();
        ok.setStatus(0);
        ok.setErrorType(null);
        ToolCallLogEntity failL3 = new ToolCallLogEntity();
        failL3.setStatus(1);
        failL3.setErrorType("L3");
        ToolCallLogEntity failL4 = new ToolCallLogEntity();
        failL4.setStatus(1);
        failL4.setErrorType("L4");
        ToolCallLogEntity timeout = new ToolCallLogEntity();
        timeout.setStatus(3);
        timeout.setErrorType(null);

        // 两次 selectList：errorType / status
        when(toolLogMapper.selectList(any()))
                .thenReturn(List.of(ok, failL3, failL4, timeout))
                .thenReturn(List.of(ok, failL3, failL4, timeout));

        ComplianceReport report = service.generateReport();

        assertThat(report.toolCall().total()).isEqualTo(4);
        assertThat(report.toolCall().byErrorType()).containsEntry("L3", 1L)
                .containsEntry("L4", 1L).containsEntry("(空)", 2L);
        assertThat(report.toolCall().byStatus()).containsEntry(0, 1L)
                .containsEntry(1, 2L).containsEntry(3, 1L);
        // 失败率 = (4 - 成功1) / 4 = 75.0
        assertThat(report.toolCall().failureRate()).isEqualTo(75.0);
    }

    @Test
    @DisplayName("投影列全为 NULL 的真实行会被 MyBatis 映射成 null 元素：聚合不得抛 NPE")
    void toleratesNullProjectedRows() {
        when(toolLogMapper.selectCount(any())).thenReturn(2L);

        ToolCallLogEntity failed = new ToolCallLogEntity();
        failed.setStatus(1);
        failed.setErrorType("L3");
        // error_type 只投影可空列：成功调用在真实 MyBatis 下是 null 行（List.of 不容 null，故用 Arrays.asList）
        when(toolLogMapper.selectList(any()))
                .thenReturn(Arrays.asList(null, failed))
                .thenReturn(List.of(failed));

        ComplianceReport report = service.generateReport();

        assertThat(report.toolCall().byErrorType()).containsEntry("(空)", 1L).containsEntry("L3", 1L);
        assertThat(report.toolCall().byStatus()).containsEntry(1, 1L);
    }

    @Test
    @DisplayName("限流与编排分节被聚合，Markdown 对 openid 脱敏")
    void aggregatesRateLimitOrchestrationAndMasksOpenid() {
        when(wxMessageMapper.selectCount(any())).thenReturn(0L);
        when(wxSessionMapper.selectCount(any())).thenReturn(0L);
        when(toolLogMapper.selectCount(any())).thenReturn(0L);
        when(petProfileMapper.selectCount(any())).thenReturn(0L);
        when(auditLogMapper.selectCount(any())).thenReturn(0L);
        when(rateLimitLogMapper.selectCount(any())).thenReturn(3L);
        when(orchestrationTraceMapper.selectCount(any())).thenReturn(2L);
        when(wxUserMapper.selectCount(any())).thenReturn(0L);

        RateLimitLogEntity user = new RateLimitLogEntity();
        user.setLimitType("USER_FREQ");
        RateLimitLogEntity ip = new RateLimitLogEntity();
        ip.setLimitType("IP_FREQ");
        ip.setLimitType("IP_FREQ");
        when(rateLimitLogMapper.selectList(any())).thenReturn(List.of(user, ip, ip));

        OrchestrationTraceEntity ok = new OrchestrationTraceEntity();
        ok.setExceededBudget(false);
        ok.setTotalMs(800);
        OrchestrationTraceEntity slow = new OrchestrationTraceEntity();
        slow.setExceededBudget(true);
        slow.setTotalMs(4200);
        when(orchestrationTraceMapper.selectList(any())).thenReturn(List.of(ok, slow));

        // 最近审计：一条携带原始 openid（应被脱敏），一条为空
        AuditLogEntity raw = new AuditLogEntity();
        raw.setId(1L);
        raw.setRegType("DATA_DELETE");
        raw.setAction("DATA_DELETE");
        raw.setTarget("openid_raw_user_12345");
        raw.setResult(1);
        AuditLogEntity empty = new AuditLogEntity();
        empty.setId(2L);
        empty.setRegType("AUTH");
        empty.setAction("ACCESS_DENIED");
        empty.setTarget(null);
        empty.setResult(0);
        Page<AuditLogEntity> recentPage = new Page<>(1, 10);
        recentPage.setRecords(List.of(raw, empty));
        recentPage.setTotal(2L);
        // selectList(scope) -> 空；selectPage -> 最近两行
        when(auditLogMapper.selectList(any())).thenReturn(List.of());
        when(auditLogMapper.selectPage(any(), any())).thenReturn(recentPage);

        ComplianceReport report = service.generateReport();
        assertThat(report.rateLimit().total()).isEqualTo(3);
        assertThat(report.rateLimit().byLimitType()).containsEntry("USER_FREQ", 1L)
                .containsEntry("IP_FREQ", 2L);
        assertThat(report.rateLimit().blockRate()).isEqualTo(66.67);
        assertThat(report.orchestration().total()).isEqualTo(2);
        assertThat(report.orchestration().byBudgetExceeded()).containsEntry("WITHIN_BUDGET", 1L)
                .containsEntry("EXCEEDED_BUDGET", 1L);
        assertThat(report.orchestration().byLatencyBucket()).containsEntry("<1s", 1L)
                .containsEntry("3-5s", 1L);

        // Markdown 渲染与脱敏
        ComplianceReportVO vo = assembler.toVO(report);
        String md = assembler.renderMarkdown(vo);
        assertThat(md).startsWith("# 衔光管家 合规报告");
        assertThat(md).contains("数据保留策略与执行");
        // 原始 openid 不得出现在报告中，脱敏形态应出现
        assertThat(md).doesNotContain("openid_raw_user_12345");
        assertThat(md).contains(MaskUtils.openid("openid_raw_user_12345"));
    }
}
