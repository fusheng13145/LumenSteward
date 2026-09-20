package com.lumensteward.clawbot.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lumensteward.clawbot.infrastructure.persistence.entity.AnomalyEventEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 四层异常事件 Mapper（SRS 2.3.5 / W1）。
 */
@Mapper
public interface AnomalyEventMapper extends BaseMapper<AnomalyEventEntity> {
}
