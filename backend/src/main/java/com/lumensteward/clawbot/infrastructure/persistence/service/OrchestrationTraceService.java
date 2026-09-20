package com.lumensteward.clawbot.infrastructure.persistence.service;

import com.lumensteward.clawbot.application.orchestrator.OrchestrationTracedEvent;
import com.lumensteward.clawbot.infrastructure.persistence.entity.OrchestrationTraceEntity;

import java.util.Optional;

/**
 * 链路时序追踪服务（A-5 超时预算 / T6）。
 *
 * <p>{@link #persist} 由 {@code OrchestrationTraceListener} 在链路结束时调用落库；
 * {@link #findByTraceId} 供管理后台监控端点按链路标识查询时序瀑布数据。
 */
public interface OrchestrationTraceService {

    /**
     * 落库一次链路时序快照（best-effort：失败不抛出，不影响主链路）。
     *
     * @param event 链路时序追踪事件（含脱敏 openid 与 span 列表）
     */
    void persist(OrchestrationTracedEvent event);

    /**
     * 按链路标识查询最近一条时序快照。
     *
     * @param traceId 链路标识
     * @return 命中的实体；无记录或入参为空时返回 {@link Optional#empty()}
     */
    Optional<OrchestrationTraceEntity> findByTraceId(String traceId);
}
