package com.lumensteward.clawbot.infrastructure.persistence.service;

import com.lumensteward.clawbot.application.anomaly.AnomalyNotice;

/**
 * 四层异常事件写入服务（SRS 2.3.5 / A-3 四层分布，迭代 4 W1）。
 *
 * <p>只做一件事：把埋点处判定的异常<b>落表</b>，使 L1 接入层与 L2 认知层异常从「只有日志」
 * 变为「可查询事实」。读侧聚合归 {@code MonitorService}（只读，不走本服务）。
 */
public interface AnomalyEventService {

    /**
     * 记录一次异常事件（best-effort：失败仅告警，绝不影响主链路）。
     *
     * @param notice 异常事件（openid 传原始值，脱敏在本方法内统一完成）
     */
    void record(AnomalyNotice notice);
}
