package com.lumensteward.clawbot.infrastructure.persistence.repository;

import com.lumensteward.clawbot.infrastructure.persistence.entity.PetProfileEntity;

import java.util.List;
import java.util.Optional;

/**
 * 宠物档案仓库（架构 5.4 / SRS FR-14）。
 *
 * <p>仅暴露领域所需的最小操作；软删除语义由实体 {@code TableLogic} 承载（存活 = {@code deleted_at}
 * 为 NULL）。唯一性由 DB 约束 {@code uk_openid_pet_name_live_marker} 兜底（架构 3.3）。
 */
public interface PetProfileRepository {

    /**
     * 插入档案。
     *
     * @param entity 实体（回填主键）
     * @return 插入行数
     */
    int insert(PetProfileEntity entity);

    /**
     * 列出存活档案。
     *
     * @param openid 用户
     * @return 实体列表
     */
    List<PetProfileEntity> listLive(String openid);

    /**
     * 按昵称查存活档案。
     *
     * @param openid  用户
     * @param petName 昵称
     * @return 实体（不存在为空）
     */
    Optional<PetProfileEntity> findLiveByName(String openid, String petName);

    /**
     * 按主键查存活档案（后台管理侧按档案 id 定位，T05）。
     *
     * @param id 主键
     * @return 实体（不存在或已软删为空）
     */
    Optional<PetProfileEntity> findLiveById(Long id);

    /**
     * 按主键更新（仅非空字段，依 MyBatis-Plus updateById 语义）。
     *
     * @param entity 实体（须含 id）
     * @return 更新行数
     */
    int updateById(PetProfileEntity entity);

    /**
     * 软删除（设置 deleted_at）。
     *
     * @param id 主键
     * @return 删除行数
     */
    int softDeleteById(Long id);

    /**
     * 存活同名是否存在。
     *
     * @param openid  用户
     * @param petName 昵称
     * @return 存在返回 true
     */
    boolean existsLive(String openid, String petName);
}
