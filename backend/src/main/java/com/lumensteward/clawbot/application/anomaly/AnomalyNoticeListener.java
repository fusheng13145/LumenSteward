package com.lumensteward.clawbot.application.anomaly;

import com.lumensteward.clawbot.infrastructure.persistence.service.AnomalyEventService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 四层异常事件监听器（SRS 2.3.5 / W1）。
 *
 * <p>监听编排器发布的 {@link AnomalyNotice}，转交 {@link AnomalyEventService} 落库。
 * 编排器因此无需新增构造器依赖（其为多任务收敛文件，且已具备 {@code ApplicationEventPublisher}）。
 * 与 {@code OrchestrationTraceListener} 同范式：监听器不得向发布方（主链路）抛出。
 */
@Component
public class AnomalyNoticeListener {

    private static final Logger log = LoggerFactory.getLogger(AnomalyNoticeListener.class);

    private final AnomalyEventService anomalyEventService;

    /**
     * 构造器注入（G-14）。
     *
     * @param anomalyEventService 异常事件写入服务
     */
    public AnomalyNoticeListener(AnomalyEventService anomalyEventService) {
        this.anomalyEventService = anomalyEventService;
    }

    /**
     * 接收异常事件并落库。
     *
     * @param notice 异常事件
     */
    @EventListener
    public void onAnomaly(AnomalyNotice notice) {
        try {
            anomalyEventService.record(notice);
        } catch (RuntimeException e) {
            log.warn("异常事件落库异常（忽略）: layer={} code={} err={}",
                    notice == null ? null : notice.layer(),
                    notice == null ? null : notice.errorCode(), e.getMessage());
        }
    }
}
