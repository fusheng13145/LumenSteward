package com.lumensteward.clawbot.infrastructure.persistence.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.lumensteward.clawbot.application.orchestrator.model.ToolCallRecord;
import com.lumensteward.clawbot.common.enums.ToolStatus;
import com.lumensteward.clawbot.common.util.JsonUtils;
import com.lumensteward.clawbot.domain.tool.ToolResult;
import com.lumensteward.clawbot.infrastructure.client.llm.dto.ToolCall;
import com.lumensteward.clawbot.infrastructure.persistence.entity.ToolCallLogEntity;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.ToolCallLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * {@link ToolCallLogService} 的同步实现（ADR-003）。
 *
 * <p>{@code logStart} 插入初始行（状态 NOT_EXECUTED）；{@code logEnd} 按主键回填终态。两处均为
 * <b>同步</b>调用，任一写失败仅记录 WARN、不抛出（DB 不可用 → 只读降级，主链路继续）。
 */
@Service
public class ToolCallLogServiceImpl implements ToolCallLogService {

    private static final Logger log = LoggerFactory.getLogger(ToolCallLogServiceImpl.class);

    private final ToolCallLogMapper toolCallLogMapper;

    /**
     * 构造器注入（G-14）。
     *
     * @param toolCallLogMapper 工具日志 Mapper
     */
    public ToolCallLogServiceImpl(ToolCallLogMapper toolCallLogMapper) {
        this.toolCallLogMapper = toolCallLogMapper;
    }

    @Override
    public ToolCallRecord logStart(String traceId, String openid, Long sessionId, ToolCall call, int round) {
        String toolName = call == null ? null : call.functionName();
        JsonNode params = call == null ? null : JsonUtils.readTree(call.argumentsJson());
        ToolCallRecord record = new ToolCallRecord(null, traceId, openid, sessionId, toolName,
                0, round, ToolStatus.NOT_EXECUTED, null, null, params, null, 0L);
        if (call == null) {
            return record;
        }
        try {
            ToolCallLogEntity entity = new ToolCallLogEntity();
            entity.setTraceId(traceId);
            entity.setOpenid(openid);
            entity.setSessionId(sessionId);
            entity.setToolName(toolName);
            entity.setCallSeq(0);
            entity.setParamsJson(truncate(params == null ? null : params.toString()));
            entity.setStatus(ToolStatus.NOT_EXECUTED.getCode());
            entity.setLatencyMs(0);
            entity.setLlmRound(round);
            toolCallLogMapper.insert(entity);
            return new ToolCallRecord(entity.getId(), traceId, openid, sessionId, toolName,
                    0, round, ToolStatus.NOT_EXECUTED, null, null, params, null, 0L);
        } catch (RuntimeException e) {
            log.warn("工具日志起始写失败（只读降级）: err={}", e.getMessage());
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
            log.warn("工具日志结束写失败（只读降级）: err={}", e.getMessage());
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
