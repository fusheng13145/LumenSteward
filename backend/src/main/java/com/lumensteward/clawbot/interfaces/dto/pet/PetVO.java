package com.lumensteward.clawbot.interfaces.dto.pet;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 宠物档案视图（架构 4.3 / GET /api/users/{id}/pets）。
 *
 * @param id           主键
 * @param openid       脱敏后的 openid
 * @param petName      宠物昵称
 * @param petType      类型（猫/狗/其他）
 * @param breed        品种
 * @param gender       性别（公/母/未知）
 * @param birthday     生日
 * @param weightKg     体重（kg）
 * @param personality  性格描述
 * @param notes        备注
 * @param createdAt    创建时间
 * @param updatedAt    更新时间
 */
public record PetVO(Long id,
                    String openid,
                    String petName,
                    String petType,
                    String breed,
                    String gender,
                    LocalDate birthday,
                    BigDecimal weightKg,
                    String personality,
                    String notes,
                    LocalDateTime createdAt,
                    LocalDateTime updatedAt) {
}
