package com.lumensteward.clawbot.application.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.lumensteward.clawbot.application.orchestrator.model.ToolCallRecord;
import com.lumensteward.clawbot.application.safety.ConsistencyChecker;
import com.lumensteward.clawbot.application.safety.ConsistencyVerdict;
import com.lumensteward.clawbot.common.enums.ToolStatus;
import com.lumensteward.clawbot.common.error.ErrorCode;
import com.lumensteward.clawbot.common.exception.BizException;
import com.lumensteward.clawbot.common.util.JsonUtils;
import com.lumensteward.clawbot.common.util.MaskUtils;
import com.lumensteward.clawbot.domain.tool.Tool;
import com.lumensteward.clawbot.domain.tool.ToolContext;
import com.lumensteward.clawbot.domain.tool.ToolRegistry;
import com.lumensteward.clawbot.domain.tool.ToolResult;
import com.lumensteward.clawbot.infrastructure.persistence.entity.ToolCallLogEntity;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.ToolCallLogMapper;
import com.lumensteward.clawbot.infrastructure.persistence.service.AuditLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 工具调用回放（A-2 / 迭代 2 T12）。
 *
 * <p>数据源为同步落库的 {@code log_tool_call}（ADR-003）。回放做两件事：
 * <ol>
 *   <li><b>干跑重放（AC①）：</b>取存储的 {@code params_json}，用<b>当前工具实现</b>重新执行，
 *       得到本次真实结果，与历史状态并列展示——用于验证"工具修好没有"。</li>
 *   <li><b>复现拦截（AC②）：</b>给定当时的回复文本 + 该链路的历史执行记录，重跑
 *       {@link ConsistencyChecker}，复现"模型声称成功但工具失败"被拦截的判定路径。</li>
 * </ol>
 *
 * <p><b>副作用护栏：</b>{@code dryRun=true}（默认）时，非只读工具（{@link Tool#readOnly()} 为 false，
 * 如写档案、下发语音）一律<b>跳过并说明原因</b>，绝不因调试动作改写业务数据；
 * 显式 {@code dryRun=false} 才实际执行，且整次回放写入 {@code log_audit} 留痕。
 */
@Service
public class ReplayService {

    private static final Logger log = LoggerFactory.getLogger(ReplayService.class);

    private final ToolCallLogMapper toolCallLogMapper;
    private final ToolRegistry toolRegistry;
    private final ConsistencyChecker consistencyChecker;
    private final AuditLogService auditLogService;

    /**
     * 构造器注入（G-14）。
     *
     * @param toolCallLogMapper   工具日志 Mapper
     * @param toolRegistry        工具注册中心（按名取当前实现）
     * @param consistencyChecker  执行一致性校验（复现拦截）
     * @param auditLogService     审计（回放操作留痕）
     */
    public ReplayService(ToolCallLogMapper toolCallLogMapper, ToolRegistry toolRegistry,
                         ConsistencyChecker consistencyChecker, AuditLogService auditLogService) {
        this.toolCallLogMapper = toolCallLogMapper;
        this.toolRegistry = toolRegistry;
        this.consistencyChecker = consistencyChecker;
        this.auditLogService = auditLogService;
    }

    /**
     * 回放单条工具调用。
     *
     * @param id      日志主键
     * @param reply   可选：当时（或假设）的回复文本，用于复现一致性拦截
     * @param dryRun  是否干跑（true 时跳过非只读工具）
     * @param adminId 操作人
     * @param ip      来源 IP
     * @return 回放结果
     */
    public ReplayResult replayById(Long id, String reply, boolean dryRun, Long adminId, String ip) {
        if (id == null) {
            throw BizException.of(ErrorCode.PARAM_MISSING, "日志 id 不能为空");
        }
        ToolCallLogEntity entity = toolCallLogMapper.selectById(id);
        if (entity == null) {
            throw BizException.of(ErrorCode.RESOURCE_NOT_FOUND, "工具日志不存在: " + id);
        }
        return replay(List.of(entity), reply, dryRun, adminId, ip);
    }

    /**
     * 回放整条链路（同一 traceId 下的全部调用，按 call_seq 升序）。
     *
     * @param traceId 链路标识
     * @param reply   可选：当时（或假设）的回复文本
     * @param dryRun  是否干跑
     * @param adminId 操作人
     * @param ip      来源 IP
     * @return 回放结果
     */
    public ReplayResult replayByTrace(String traceId, String reply, boolean dryRun, Long adminId, String ip) {
        if (traceId == null || traceId.isBlank()) {
            throw BizException.of(ErrorCode.PARAM_MISSING, "traceId 不能为空");
        }
        List<ToolCallLogEntity> logs = toolCallLogMapper.selectList(
                new LambdaQueryWrapper<ToolCallLogEntity>()
                        .eq(ToolCallLogEntity::getTraceId, traceId)
                        .orderByAsc(ToolCallLogEntity::getCallSeq)
                        .orderByAsc(ToolCallLogEntity::getId));
        if (logs.isEmpty()) {
            throw BizException.of(ErrorCode.RESOURCE_NOT_FOUND, "该链路无工具调用记录: " + traceId);
        }
        return replay(logs, reply, dryRun, adminId, ip);
    }

    /**
     * 执行回放（含可选的一致性复现）。
     *
     * @param logs    待回放的调用记录
     * @param reply   可选回复文本
     * @param dryRun  是否干跑
     * @param adminId 操作人
     * @param ip      来源 IP
     * @return 回放结果
     */
    private ReplayResult replay(List<ToolCallLogEntity> logs, String reply, boolean dryRun,
                                Long adminId, String ip) {
        List<ReplayItem> items = logs.stream().map(entity -> replayOne(entity, dryRun)).toList();
        ConsistencyReplay consistency = replayConsistency(reply, logs);
        String traceId = logs.isEmpty() ? null : logs.get(0).getTraceId();
        String openid = logs.isEmpty() ? null : MaskUtils.openid(logs.get(0).getOpenid());
        ReplayResult result = new ReplayResult(traceId, openid, items, consistency, dryRun);
        audit(adminId, traceId, result.summary(), ip);
        log.info("工具调用回放完成 traceId={} items={} dryRun={} consistencyIntercepted={}",
                traceId, items.size(), dryRun, consistency.intercepted());
        return result;
    }

    /**
     * 单条重放。
     *
     * @param entity 历史记录
     * @param dryRun 是否干跑
     * @return 回放项
     */
    private ReplayItem replayOne(ToolCallLogEntity entity, boolean dryRun) {
        String toolName = entity.getToolName();
        Optional<Tool> toolOpt = toolRegistry == null ? Optional.empty() : toolRegistry.find(toolName);
        if (toolOpt.isEmpty()) {
            return new ReplayItem(entity.getId(), toolName, entity.getParamsJson(),
                    statusLabel(entity.getStatus()), ToolStatus.NOT_EXECUTED.name(),
                    "TOOL_NOT_FOUND", "工具未注册，无法回放（当前已注册: "
                            + (toolRegistry == null ? "[]" : toolRegistry.names()) + "）",
                    null, 0L, true, "TOOL_NOT_FOUND");
        }
        Tool tool = toolOpt.get();
        if (dryRun && !tool.readOnly()) {
            return new ReplayItem(entity.getId(), toolName, entity.getParamsJson(),
                    statusLabel(entity.getStatus()), ToolStatus.NOT_EXECUTED.name(),
                    "REPLAY_SKIPPED", "干跑模式跳过非只读工具（避免副作用）；如需实际执行请关闭干跑",
                    null, 0L, true, "NOT_READ_ONLY");
        }
        JsonNode args = safeParse(entity.getParamsJson());
        long start = System.currentTimeMillis();
        ToolResult outcome;
        try {
            outcome = tool.execute(ToolContext.of(entity.getTraceId(), entity.getOpenid(),
                    entity.getSessionId(), entity.getLlmRound() == null ? 0 : entity.getLlmRound()), args);
        } catch (RuntimeException e) {
            log.warn("回放执行异常: tool={} err={}", toolName, e.getMessage());
            outcome = ToolResult.failure("TOOL_FAILED", "回放执行异常（已转结构化失败）", false);
        }
        long latency = System.currentTimeMillis() - start;
        String dataJson = outcome == null || outcome.data() == null ? null : outcome.data().toString();
        return new ReplayItem(entity.getId(), toolName, entity.getParamsJson(),
                statusLabel(entity.getStatus()), outcome == null ? ToolStatus.FAILED.name() : outcome.status().name(),
                outcome == null ? "TOOL_FAILED" : outcome.errorType(),
                outcome == null ? "回放未返回结果" : outcome.message(),
                dataJson, latency, false, null);
    }

    /**
     * 一致性复现：以历史执行记录 + 给定回复重跑校验。
     *
     * @param reply 回复文本（可空）
     * @param logs  历史记录
     * @return 复现结果；未给回复时 {@code replayed=false}
     */
    private ConsistencyReplay replayConsistency(String reply, List<ToolCallLogEntity> logs) {
        if (reply == null || reply.isBlank() || consistencyChecker == null) {
            return new ConsistencyReplay(false, false, null, null);
        }
        List<ToolCallRecord> records = logs.stream().map(ReplayService::toRecord).toList();
        ConsistencyVerdict verdict;
        try {
            verdict = consistencyChecker.check(reply, records);
        } catch (RuntimeException e) {
            log.warn("一致性复现失败: err={}", e.getMessage());
            return new ConsistencyReplay(true, false, null, "校验执行失败: " + e.getMessage());
        }
        boolean intercepted = !verdict.passed();
        return new ConsistencyReplay(true, intercepted,
                verdict.claimText(), verdict.reason() == null ? null : verdict.reason().name());
    }

    /**
     * 历史日志 → 编排侧记录（状态取历史值，用于复现当时的判定）。
     *
     * @param entity 历史记录
     * @return 工具调用记录
     */
    private static ToolCallRecord toRecord(ToolCallLogEntity entity) {
        ToolStatus status = ToolStatus.findByCode(entity.getStatus() == null ? 4 : entity.getStatus())
                .orElse(ToolStatus.NOT_EXECUTED);
        return new ToolCallRecord(entity.getId(), entity.getTraceId(), entity.getOpenid(),
                entity.getSessionId(), entity.getToolName(),
                entity.getCallSeq() == null ? 0 : entity.getCallSeq(),
                entity.getLlmRound() == null ? 0 : entity.getLlmRound(),
                status, entity.getErrorType(), entity.getFallbackReason(),
                safeParse(entity.getParamsJson()), safeParse(entity.getResultJson()),
                entity.getLatencyMs() == null ? 0L : entity.getLatencyMs());
    }

    private static JsonNode safeParse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return JsonUtils.readTree(json);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String statusLabel(Integer code) {
        return ToolStatus.findByCode(code == null ? 4 : code).orElse(ToolStatus.NOT_EXECUTED).name();
    }

    private void audit(Long adminId, String traceId, String summary, String ip) {
        if (auditLogService == null) {
            return;
        }
        try {
            auditLogService.record(adminId, "TOOL_REPLAY", "REPLAY", traceId, null, summary,
                    "工具调用回放", ip, 1);
        } catch (RuntimeException e) {
            log.warn("回放审计写失败: err={}", e.getMessage());
        }
    }

    /**
     * 回放结果。
     *
     * @param traceId     链路标识
     * @param openid      用户（已脱敏）
     * @param items       逐条回放项
     * @param consistency 一致性复现结果
     * @param dryRun      是否干跑
     */
    public record ReplayResult(String traceId, String openid, List<ReplayItem> items,
                               ConsistencyReplay consistency, boolean dryRun) {

        /**
         * 结果摘要（用于审计留痕）。
         *
         * @return 摘要文本
         */
        public String summary() {
            long skipped = items.stream().filter(ReplayItem::skipped).count();
            return "items=" + items.size() + " skipped=" + skipped
                    + " dryRun=" + dryRun + " intercepted=" + consistency.intercepted();
        }
    }

    /**
     * 单条回放项。
     *
     * @param logId          历史日志主键
     * @param toolName       工具名
     * @param paramsJson     历史入参
     * @param originalStatus 历史状态
     * @param replayStatus   本次回放状态
     * @param errorType      异常分类
     * @param message        说明（跳过时给出跳过原因）
     * @param dataJson       本次结果（JSON 字符串）
     * @param latencyMs      本次耗时
     * @param skipped        是否被跳过
     * @param skipReason     跳过原因
     */
    public record ReplayItem(Long logId, String toolName, String paramsJson, String originalStatus,
                             String replayStatus, String errorType, String message, String dataJson,
                             long latencyMs, boolean skipped, String skipReason) {
    }

    /**
     * 一致性复现结果。
     *
     * @param replayed    是否执行了复现（需提供回复文本）
     * @param intercepted 是否复现出拦截（true 即"模型声称成功但工具失败"被拦下）
     * @param claimText   命中问题的声明原文
     * @param reason      判定原因
     */
    public record ConsistencyReplay(boolean replayed, boolean intercepted, String claimText,
                                    String reason) {
    }
}
