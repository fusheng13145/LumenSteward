package com.lumensteward.clawbot.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lumensteward.clawbot.infrastructure.persistence.entity.SysConfigEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 系统配置 Mapper（{@code sys_config}）。
 */
@Mapper
public interface SysConfigMapper extends BaseMapper<SysConfigEntity> {
}
