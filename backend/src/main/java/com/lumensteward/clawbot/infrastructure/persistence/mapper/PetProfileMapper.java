package com.lumensteward.clawbot.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lumensteward.clawbot.infrastructure.persistence.entity.PetProfileEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 宠物档案 Mapper（{@code biz_pet_profile}）。
 *
 * <p>注意：{@code live_marker} 为数据库生成列，{@link PetProfileEntity} 未声明该字段；
 * 唯一性由数据库约束 {@code uk_openid_pet_name_live_marker} 兜底，应用层另在写事务内查重（架构 3.3）。
 */
@Mapper
public interface PetProfileMapper extends BaseMapper<PetProfileEntity> {

    /**
     * 物理清除：忽略 MyBatis-Plus 逻辑删除（{@code deleted_at IS NULL} 过滤），
     * 直接删除 {@code deleted_at} 早于截止时间的记录（FR-19 ①：软删 30 天后物理清除）。
     *
     * @param cutoff 截止时间（早于此时间且已软删的记录被清除）
     * @return 删除行数
     */
    @Update("DELETE FROM biz_pet_profile WHERE deleted_at IS NOT NULL AND deleted_at < #{cutoff}")
    int deletePhysicallyDeletedBefore(@Param("cutoff") LocalDateTime cutoff);

    /**
     * 按 openid 物理删除全部档案（FR-19 用户删除请求，忽略逻辑删除）。
     *
     * @param openid 用户标识
     * @return 删除行数
     */
    @Update("DELETE FROM biz_pet_profile WHERE openid = #{openid}")
    int deleteAllByOpenid(@Param("openid") String openid);
}
