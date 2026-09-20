package com.lumensteward.clawbot.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lumensteward.clawbot.infrastructure.persistence.entity.OrchestrationTraceEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 链路时序追踪 Mapper（A-5 / T6）。
 */
@Mapper
public interface OrchestrationTraceMapper extends BaseMapper<OrchestrationTraceEntity> {
}
