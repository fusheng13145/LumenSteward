package com.lumensteward.clawbot.infrastructure.persistence.service;

import com.lumensteward.clawbot.application.anomaly.AnomalyNotice;
import com.lumensteward.clawbot.common.util.MaskUtils;
import com.lumensteward.clawbot.infrastructure.observability.TraceContext;
import com.lumensteward.clawbot.infrastructure.persistence.entity.AnomalyEventEntity;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.AnomalyEventMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * {@link AnomalyEventService} 的 MyBatis-Plus 实现（W1）。
 *
 * <p><b>脱敏集中</b>：openid 一律经 {@link MaskUtils#openid} 落库，埋点处无需（也不应）自行脱敏，
 * 避免多处遗漏（BR-21）。<b>detail 截断</b>至 {@value #DETAIL_MAX_LENGTH} 字符，与列宽一致，
 * 且只放异常码/原因这类非 PII 摘要。
 *
 * <p><b>best-effort</b>：观测写入失败仅记 WARN，不抛出——异常分布是看板数据，不得反过来
 * 影响消息主链路（与 {@code OrchestrationTraceServiceImpl} 同一纪律）。
 */
@Service
public class AnomalyEventServiceImpl implements AnomalyEventService {

    private static final Logger log = LoggerFactory.getLogger(AnomalyEventServiceImpl.class);

    /** detail 列宽上限（与 {@code log_anomaly_event.detail} 一致）。 */
    static final int DETAIL_MAX_LENGTH = 255;

    private final AnomalyEventMapper mapper;

    /**
     * 构造器注入（G-14，单构造器由 Spring 自动装配）。
     *
     * @param mapper 异常事件 Mapper
     */
    public AnomalyEventServiceImpl(AnomalyEventMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void record(AnomalyNotice notice) {
        if (notice == null || notice.layer() == null || notice.errorCode() == null) {
            return;
        }
        try {
            AnomalyEventEntity entity = new AnomalyEventEntity();
            entity.setLayer(notice.layer().name());
            entity.setErrorCode(notice.errorCode());
            entity.setSource(notice.source());
            entity.setTraceId(TraceContext.getTraceId());
            entity.setOpenid(MaskUtils.openid(notice.openid()));
            entity.setDetail(truncate(notice.detail()));
            mapper.insert(entity);
        } catch (RuntimeException e) {
            log.warn("异常事件落库失败（忽略，不影响主链路）: layer={} code={} err={}",
                    notice.layer(), notice.errorCode(), e.getMessage());
        }
    }

    private static String truncate(String detail) {
        if (detail == null || detail.length() <= DETAIL_MAX_LENGTH) {
            return detail;
        }
        return detail.substring(0, DETAIL_MAX_LENGTH);
    }
}
