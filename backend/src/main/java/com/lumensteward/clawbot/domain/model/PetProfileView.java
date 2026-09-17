package com.lumensteward.clawbot.domain.model;

import com.lumensteward.clawbot.infrastructure.persistence.entity.PetProfileEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 宠物档案视图（架构 5.3 / SRS FR-14）。
 *
 * @param id           主键
 * @param openid       所属用户
 * @param petName      宠物昵称
 * @param petType      类型（猫/狗/其他）
 * @param breed        品种
 * @param gender       性别（公/母/未知）
 * @param birthday     生日
 * @param weightKg     体重（kg）
 * @param personality  性格描述
 * @param notes        备注
 * @param photoMediaId 头像素材 id
 * @param createdAt    创建时间
 * @param updatedAt    更新时间
 */
public record PetProfileView(Long id, String openid, String petName, String petType, String breed,
                             String gender, LocalDate birthday, BigDecimal weightKg, String personality,
                             String notes, String photoMediaId, LocalDateTime createdAt,
                             LocalDateTime updatedAt) {

    /** 由实体转换为视图。 */
    public static PetProfileView from(PetProfileEntity entity) {
        if (entity == null) {
            return null;
        }
        return new PetProfileView(entity.getId(), entity.getOpenid(), entity.getPetName(),
                entity.getPetType(), entity.getBreed(), entity.getGender(), entity.getBirthday(),
                entity.getWeightKg(), entity.getPersonality(), entity.getNotes(),
                entity.getPhotoMediaId(), entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
