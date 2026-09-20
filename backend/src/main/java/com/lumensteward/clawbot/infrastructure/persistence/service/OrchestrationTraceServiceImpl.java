package com.lumensteward.clawbot.infrastructure.persistence.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lumensteward.clawbot.application.orchestrator.OrchestrationTracedEvent;
import com.lumensteward.clawbot.common.util.JsonUtils;
import com.lumensteward.clawbot.infrastructure.persistence.entity.OrchestrationTraceEntity;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.OrchestrationTraceMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * {@link OrchestrationTraceService} 的 MyBatis-Plus 实现（A-5 / T6）。
 *
 * <p>写失败绝不抛出（BR-29 / 9.5 DB 不可用 → 只读降级）：落库异常仅记 WARN，
 * 保证观测通道的写入不影响编排主链路。
 */
@Service
public class OrchestrationTraceServiceImpl implements OrchestrationTraceService {

    private static final Logger log = LoggerFactory.getLogger(OrchestrationTraceServiceImpl.class);

    private final OrchestrationTraceMapper mapper;

    /**
     * 构造器注入（G-14）。
     *
     * @param mapper 链路时序 Mapper
     */
    public OrchestrationTraceServiceImpl(OrchestrationTraceMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void persist(OrchestrationTracedEvent event) {
        if (event == null) {
            return;
        }
        try {
            OrchestrationTraceEntity entity = new OrchestrationTraceEntity();
            entity.setTraceId(event.traceId());
            // 事件构造时已脱敏，此处不再二次处理，避免重复打码
            entity.setOpenid(event.openid());
            entity.setSessionId(event.sessionId());
            entity.setTotalMs((int) event.totalMs());
            entity.setTotalBudgetMs(event.totalBudgetMs());
            entity.setRounds(event.rounds());
            entity.setExceededBudget(event.exceededBudget());
            entity.setSpanJson(JsonUtils.toJson(event.spans()));
            mapper.insert(entity);
        } catch (RuntimeException e) {
            // 落库失败不影响主链路（观测通道 best-effort）
            log.warn("链路时序落库失败（忽略，不影响主链路）: traceId={} err={}",
                    event.traceId(), e.getMessage());
        }
    }

    @Override
    public Optional<OrchestrationTraceEntity> findByTraceId(String traceId) {
        if (traceId == null || traceId.isBlank()) {
            return Optional.empty();
        }
        LambdaQueryWrapper<OrchestrationTraceEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(OrchestrationTraceEntity::getTraceId, traceId)
                .orderByDesc(OrchestrationTraceEntity::getCreatedAt)
                .last("limit 1");
        return Optional.ofNullable(mapper.selectOne(wrapper));
    }
}
