package com.lumensteward.clawbot.infrastructure.persistence.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.lumensteward.clawbot.application.orchestrator.model.ToolCallRecord;
import com.lumensteward.clawbot.common.enums.ToolStatus;
import com.lumensteward.clawbot.common.util.JsonUtils;
import com.lumensteward.clawbot.domain.tool.ToolResult;
import com.lumensteward.clawbot.infrastructure.client.llm.dto.ToolCall;
import com.lumensteward.clawbot.infrastructure.observability.PersistenceWriteFailureReporter;
import com.lumensteward.clawbot.infrastructure.persistence.entity.ToolCallLogEntity;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.ToolCallLogMapper;
import org.springframework.stereotype.Service;

/**
 * {@link ToolCallLogService} 的同步实现（ADR-003）。
 *
 * <p>{@code logStart} 插入初始行（状态 NOT_EXECUTED）；{@code logEnd} 按主键回填终态。两处均为
 * <b>同步</b>调用，任一写失败不抛出（DB 不可用 → 只读降级，主链路继续），但<b>不再静默</b>：
 * 经 {@link PersistenceWriteFailureReporter} 以 ERROR 级日志（含表名/列名/完整异常）并计入指标
 * {@code persistence.write.failures}（D7 修复：此前 {@code Data too long for column 'trace_id'}
 * 因只记 WARN 且丢失列名而完全不可见，导致 {@code log_tool_call} 运行期恒空）。
 */
@Service
public class ToolCallLogServiceImpl implements ToolCallLogService {

    /** 本服务写入的目标表名（用于失败上报）。 */
    private static final String TABLE = "log_tool_call";

    private final ToolCallLogMapper toolCallLogMapper;
    private final PersistenceWriteFailureReporter writeFailureReporter;

    /**
     * 构造器注入（G-14）。
     *
     * @param toolCallLogMapper     工具日志 Mapper
     * @param writeFailureReporter  写入失败上报器（日志 + 指标）
     */
    public ToolCallLogServiceImpl(ToolCallLogMapper toolCallLogMapper,
                                  PersistenceWriteFailureReporter writeFailureReporter) {
        this.toolCallLogMapper = toolCallLogMapper;
        this.writeFailureReporter = writeFailureReporter;
    }

    @Override
    public ToolCallRecord logStart(String traceId, String openid, Long sessionId, ToolCall call,
                                   int round, int callSeq) {
        String toolName = call == null ? null : call.functionName();
        JsonNode params = call == null ? null : JsonUtils.readTree(call.argumentsJson());
        ToolCallRecord record = new ToolCallRecord(null, traceId, openid, sessionId, toolName,
                callSeq, round, ToolStatus.NOT_EXECUTED, null, null, params, null, 0L);
        if (call == null) {
            return record;
        }
        try {
            ToolCallLogEntity entity = new ToolCallLogEntity();
            entity.setTraceId(traceId);
            entity.setOpenid(openid);
            entity.setSessionId(sessionId);
            entity.setToolName(toolName);
            entity.setCallSeq(callSeq);
            entity.setParamsJson(truncate(params == null ? null : params.toString()));
            entity.setStatus(ToolStatus.NOT_EXECUTED.getCode());
            entity.setLatencyMs(0);
            entity.setLlmRound(round);
            toolCallLogMapper.insert(entity);
            return new ToolCallRecord(entity.getId(), traceId, openid, sessionId, toolName,
                    callSeq, round, ToolStatus.NOT_EXECUTED, null, null, params, null, 0L);
        } catch (RuntimeException e) {
            writeFailureReporter.report(TABLE, e);
            return record;
        }
    }

    @Override
    public void logEnd(ToolCallRecord record, ToolResult result) {
        if (record == null || record.id() == null) {
            return;
        }
        ToolCallRecord finalRecord = record.withOutcome(result, result == null ? 0L : result.latencyMs());
        try {
            ToolCallLogEntity entity = new ToolCallLogEntity();
            entity.setId(record.id());
            entity.setStatus(finalRecord.status().getCode());
            entity.setErrorType(finalRecord.errorType());
            entity.setFallbackReason(finalRecord.fallbackReason());
            entity.setResultJson(truncate(finalRecord.result() == null ? null : finalRecord.result().toString()));
            entity.setLatencyMs((int) Math.min(Integer.MAX_VALUE, Math.max(0L, finalRecord.latencyMs())));
            toolCallLogMapper.updateById(entity);
        } catch (RuntimeException e) {
            writeFailureReporter.report(TABLE, e);
        }
    }

    /** 大结果截断（SRS 7.2：大结果可截断，保留关键字段）。 */
    private static String truncate(String json) {
        if (json == null) {
            return null;
        }
        int max = 2000;
        return json.length() <= max ? json : json.substring(0, max);
    }
}
