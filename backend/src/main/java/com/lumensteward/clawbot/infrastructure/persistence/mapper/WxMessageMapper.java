package com.lumensteward.clawbot.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lumensteward.clawbot.infrastructure.persistence.entity.WxMessageEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 消息 Mapper（{@code wx_message}）。
 */
@Mapper
public interface WxMessageMapper extends BaseMapper<WxMessageEntity> {
}
