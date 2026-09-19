package com.lumensteward.clawbot.infrastructure.persistence.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lumensteward.clawbot.infrastructure.persistence.entity.RateLimitLogEntity;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.RateLimitLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * {@link RateLimitLogService} 实现（FR-20 ④）。
 */
@Service
public class RateLimitLogServiceImpl implements RateLimitLogService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitLogServiceImpl.class);

    private final RateLimitLogMapper mapper;

    /**
     * 构造器注入（G-14）。
     *
     * @param mapper 限流日志 Mapper
     */
    public RateLimitLogServiceImpl(RateLimitLogMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void record(String maskedOpenid, String ip, String limitType, LocalDateTime hitAt) {
        try {
            RateLimitLogEntity entity = new RateLimitLogEntity();
            entity.setOpenid(maskedOpenid);
            entity.setIp(ip);
            entity.setLimitType(limitType);
            entity.setHitAt(hitAt == null ? LocalDateTime.now() : hitAt);
            mapper.insert(entity);
        } catch (RuntimeException e) {
            // 限流日志写入失败绝不影响主链路（BR-29 保护优先）
            log.warn("限流事件落库失败（忽略）: err={}", e.getMessage());
        }
    }

    @Override
    public List<RateLimitLogEntity> query(String limitType, LocalDateTime start, LocalDateTime end, int limit) {
        LambdaQueryWrapper<RateLimitLogEntity> wrapper = new LambdaQueryWrapper<>();
        if (limitType != null && !limitType.isBlank()) {
            wrapper.eq(RateLimitLogEntity::getLimitType, limitType);
        }
        if (start != null) {
            wrapper.ge(RateLimitLogEntity::getHitAt, start);
        }
        if (end != null) {
            wrapper.le(RateLimitLogEntity::getHitAt, end);
        }
        wrapper.orderByDesc(RateLimitLogEntity::getHitAt);
        if (limit > 0) {
            wrapper.last("limit " + limit);
        }
        return mapper.selectList(wrapper);
    }
}
