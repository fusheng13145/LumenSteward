package com.lumensteward.clawbot.infrastructure.persistence.service;

import com.lumensteward.clawbot.infrastructure.persistence.entity.RateLimitLogEntity;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 限流事件日志服务（FR-20 ④：限流事件须可在监控页检索 / FR-17）。
 */
public interface RateLimitLogService {

    /**
     * 记录一次限流事件。
     *
     * @param maskedOpenid 脱敏后的用户标识
     * @param ip           来源 IP
     * @param limitType    限流类型（USER_FREQ / IP_FREQ）
     * @param hitAt        触发时间
     */
    void record(String maskedOpenid, String ip, String limitType, LocalDateTime hitAt);

    /**
     * 按类型与时间段分页检索（FR-17 ④）。
     *
     * @param limitType 限流类型（可空）
     * @param start     起始时间（可空）
     * @param end       结束时间（可空）
     * @param limit     返回条数上限
     * @return 事件列表（按触发时间倒序）
     */
    List<RateLimitLogEntity> query(String limitType, LocalDateTime start, LocalDateTime end, int limit);
}
