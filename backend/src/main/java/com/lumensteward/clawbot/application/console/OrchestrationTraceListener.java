package com.lumensteward.clawbot.application.console;

import com.lumensteward.clawbot.application.orchestrator.OrchestrationTracedEvent;
import com.lumensteward.clawbot.infrastructure.persistence.service.OrchestrationTraceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 链路时序追踪监听器（A-5 / T6）。
 *
 * <p>监听编排器发布的 {@link OrchestrationTracedEvent}，转交 {@link OrchestrationTraceService}
 * 落库 {@code log_orchestration_trace}。与编排器解耦：编排器仅 {@code publishEvent}，
 * 本组件负责持久化，<b>落库异常内部捕获记 WARN</b>，绝不影响主链路（与 {@code ConsoleEventRelay} 同范式）。
 */
@Component
public class OrchestrationTraceListener {

    private static final Logger log = LoggerFactory.getLogger(OrchestrationTraceListener.class);

    private final OrchestrationTraceService traceService;

    /**
     * 构造器注入（G-14）。
     *
     * @param traceService 链路时序追踪服务
     */
    public OrchestrationTraceListener(OrchestrationTraceService traceService) {
        this.traceService = traceService;
    }

    /**
     * 接收链路时序事件并落库。
     *
     * @param event 链路时序追踪事件
     */
    @EventListener
    public void onTraced(OrchestrationTracedEvent event) {
        try {
            traceService.persist(event);
        } catch (RuntimeException e) {
            // 双保险：即便 persist 未按契约吞异常，监听器也不得向发布方（主链路）抛出
            log.warn("链路时序落库异常（忽略）: traceId={} err={}",
                    event == null ? null : event.traceId(), e.getMessage());
        }
    }
}
