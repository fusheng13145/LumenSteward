package com.lumensteward.clawbot.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lumensteward.clawbot.infrastructure.persistence.entity.RateLimitLogEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 限流事件日志 Mapper（FR-20 ④ / FR-17 可检索）。
 */
@Mapper
public interface RateLimitLogMapper extends BaseMapper<RateLimitLogEntity> {
}
