package com.lumensteward.clawbot.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lumensteward.clawbot.infrastructure.persistence.entity.WxSessionEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 会话 Mapper（{@code wx_session}）。
 */
@Mapper
public interface WxSessionMapper extends BaseMapper<WxSessionEntity> {
}
