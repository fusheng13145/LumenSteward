package com.lumensteward.clawbot.application.anomaly;

import com.lumensteward.clawbot.common.enums.AnomalyLayer;

/**
 * 四层异常事件（SRS 2.3.5 / A-3 四层分布，迭代 4 W1）。
 *
 * <p>由埋点处构造，经 {@code AnomalyEventService} 落 {@code log_anomaly_event}；
 * 编排器等不便直接依赖写服务的场合，以 {@code ApplicationEventPublisher} 发布本记录，
 * 由 {@code AnomalyNoticeListener} 转交落库（与 {@code OrchestrationTracedEvent} 同一套路）。
 *
 * @param layer     异常层次（显式给定，不落 0 的猜测）
 * @param errorCode 异常码（如 {@code SIGNATURE_INVALID} / {@code LLM_TIMEOUT}）
 * @param source    埋点来源组件（如 {@code wechat.callback} / {@code dispatcher} / {@code orchestrator}）
 * @param openid    用户标识（<b>原始值</b>，脱敏统一由落库侧完成，BR-21）
 * @param detail    异常摘要（截断与 PII 约束由落库侧兜底）
 */
public record AnomalyNotice(AnomalyLayer layer, String errorCode, String source,
                            String openid, String detail) {

    /**
     * 便捷构造：层次与异常码为必填，其余可空。
     *
     * @param layer     异常层次
     * @param errorCode 异常码
     * @param source    来源组件
     * @return 事件记录
     */
    public static AnomalyNotice of(AnomalyLayer layer, String errorCode, String source) {
        return new AnomalyNotice(layer, errorCode, source, null, null);
    }
}
