package com.lumensteward.clawbot.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lumensteward.clawbot.infrastructure.persistence.entity.ToolCallLogEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 工具调用日志 Mapper（{@code log_tool_call}）。
 */
@Mapper
public interface ToolCallLogMapper extends BaseMapper<ToolCallLogEntity> {
}
