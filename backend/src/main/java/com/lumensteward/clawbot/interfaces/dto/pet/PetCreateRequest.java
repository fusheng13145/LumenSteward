package com.lumensteward.clawbot.interfaces.dto.pet;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 新增宠物档案请求（架构 4.3 / POST /api/users/{id}/pets）。
 *
 * @param petName     宠物昵称（必填）
 * @param petType     类型（猫/狗/其他）
 * @param breed       品种
 * @param gender      性别（公/母/未知）
 * @param birthday    生日（≤ 今日）
 * @param weightKg    体重（kg）
 * @param personality 性格描述
 * @param notes       备注
 */
public record PetCreateRequest(
        @NotBlank(message = "宠物昵称不能为空")
        @Size(max = 32, message = "宠物昵称不得超过 32 字符") String petName,
        String petType,
        String breed,
        String gender,
        LocalDate birthday,
        BigDecimal weightKg,
        String personality,
        String notes) {
}
