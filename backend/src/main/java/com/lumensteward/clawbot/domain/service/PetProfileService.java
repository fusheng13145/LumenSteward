package com.lumensteward.clawbot.domain.service;

import com.lumensteward.clawbot.domain.model.PetProfileCommand;
import com.lumensteward.clawbot.domain.model.PetProfilePatch;
import com.lumensteward.clawbot.domain.model.PetProfileView;

import java.util.List;
import java.util.Optional;

/**
 * 宠物档案领域服务（架构 5.3 / SRS FR-14）。
 *
 * <p>唯一真实工具 {@code manage_pet_profile} 的后端能力；承载 CRUD、字段校验（BR-02）、
 * 昵称唯一（BR-03）、软删除（BR-18）。
 */
public interface PetProfileService {

    /**
     * 创建档案。
     *
     * @param openid 用户
     * @param cmd    创建命令
     * @return 档案视图
     */
    PetProfileView create(String openid, PetProfileCommand cmd);

    /**
     * 列出存活档案。
     *
     * @param openid 用户
     * @return 档案列表
     */
    List<PetProfileView> listLive(String openid);

    /**
     * 按昵称查找存活档案。
     *
     * @param openid  用户
     * @param petName 昵称
     * @return 档案（不存在为空）
     */
    Optional<PetProfileView> findLiveByName(String openid, String petName);

    /**
     * 增量更新。
     *
     * @param openid  用户
     * @param petName 昵称（定位条件）
     * @param patch   补丁
     * @return 更新后视图
     */
    PetProfileView update(String openid, String petName, PetProfilePatch patch);

    /**
     * 软删除（BR-18）。
     *
     * @param openid  用户
     * @param petName 昵称
     */
    void softDelete(String openid, String petName);
}
