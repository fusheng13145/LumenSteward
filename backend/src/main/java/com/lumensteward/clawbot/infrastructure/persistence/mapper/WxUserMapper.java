package com.lumensteward.clawbot.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lumensteward.clawbot.infrastructure.persistence.entity.WxUserEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 微信用户 Mapper（{@code wx_user}）。
 *
 * <p>仅继承 MyBatis-Plus {@link BaseMapper} 以获得基础 CRUD 与分页能力；复杂查询在 T03/T05 按需补充。
 * {@code @Mapper} 使起始类所在包下的接口被自动扫描注册（无需额外 {@code @MapperScan}）。
 */
@Mapper
public interface WxUserMapper extends BaseMapper<WxUserEntity> {
}
