package com.lumensteward.clawbot.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lumensteward.clawbot.infrastructure.persistence.entity.PetProfileEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 宠物档案 Mapper（{@code biz_pet_profile}）。
 *
 * <p>注意：{@code live_marker} 为数据库生成列，{@link PetProfileEntity} 未声明该字段；
 * 唯一性由数据库约束 {@code uk_openid_pet_name_live_marker} 兜底，应用层另在写事务内查重（架构 3.3）。
 */
@Mapper
public interface PetProfileMapper extends BaseMapper<PetProfileEntity> {
}
