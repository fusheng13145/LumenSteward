package com.lumensteward.clawbot.infrastructure.persistence.service;

import com.lumensteward.clawbot.application.ratelimit.CostBudgetService.LlmCallUsage;
import com.lumensteward.clawbot.common.util.MaskUtils;
import com.lumensteward.clawbot.infrastructure.observability.TraceContext;
import com.lumensteward.clawbot.infrastructure.persistence.entity.LlmCallEntity;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.LlmCallMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * {@link LlmCallLogService} 的 MyBatis-Plus 实现（W5）。
 *
 * <p><b>脱敏集中</b>：openid 一律经 {@link MaskUtils#openid} 落库，调用点无需（也不应）自行脱敏
 * （BR-21）。<b>traceId 兜底</b>：意图分类等调用点不持有 traceId，回落当前线程 MDC 值。
 *
 * <p><b>best-effort</b>：观测写入失败仅记 WARN，不抛出——计量是看板数据，不得反过来影响
 * 消息主链路（与 {@code AnomalyEventServiceImpl} 同一纪律）。
 */
@Service
public class LlmCallLogServiceImpl implements LlmCallLogService {

    private static final Logger log = LoggerFactory.getLogger(LlmCallLogServiceImpl.class);

    private final LlmCallMapper mapper;

    /**
     * 构造器注入（G-14）。
     *
     * @param mapper LLM 调用计量 Mapper
     */
    public LlmCallLogServiceImpl(LlmCallMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void record(LlmCallUsage usage) {
        if (usage == null || usage.purpose() == null) {
            return;
        }
        try {
            LlmCallEntity entity = new LlmCallEntity();
            entity.setPurpose(usage.purpose().name());
            entity.setProvider(usage.provider());
            entity.setModel(usage.model());
            entity.setOpenid(MaskUtils.openid(usage.openid()));
            entity.setSessionId(usage.sessionId());
            entity.setTraceId(usage.traceId() != null ? usage.traceId() : TraceContext.getTraceId());
            entity.setPromptTokens(Math.max(0, usage.promptTokens()));
            entity.setCompletionTokens(Math.max(0, usage.completionTokens()));
            entity.setTotalTokens(Math.max(0, usage.totalTokens()));
            mapper.insert(entity);
        } catch (RuntimeException e) {
            log.warn("LLM 调用计量落库失败（忽略，不影响主链路）: purpose={} err={}",
                    usage.purpose(), e.getMessage());
        }
    }
}
