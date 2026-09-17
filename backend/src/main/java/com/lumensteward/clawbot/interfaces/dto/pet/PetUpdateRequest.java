package com.lumensteward.clawbot.interfaces.dto.pet;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 更新宠物档案请求（架构 4.3 / PUT /api/pets/{id}）。
 *
 * <p>全字段可空，仅更新非空项（AC-C4 增量更新）。
 *
 * @param petType     新类型
 * @param breed       新品种
 * @param gender      新性别
 * @param birthday    新生日
 * @param weightKg    新体重
 * @param personality 新性格描述
 * @param notes       新备注
 */
public record PetUpdateRequest(String petType,
                               String breed,
                               String gender,
                               LocalDate birthday,
                               BigDecimal weightKg,
                               String personality,
                               String notes) {
}
