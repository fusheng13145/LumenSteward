package com.lumensteward.clawbot.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 宠物档案增量补丁（架构 5.3 / SRS FR-14 AC-C4）。
 *
 * <p>全字段可空，<b>仅更新非空项</b>：用户"只改生日不动其他"时，其余字段保持原值。
 *
 * @param petType     新类型
 * @param breed       新品种
 * @param gender      新性别
 * @param birthday    新生日
 * @param weightKg    新体重
 * @param personality 新性格描述
 * @param notes       新备注
 */
public record PetProfilePatch(String petType, String breed, String gender, LocalDate birthday,
                              BigDecimal weightKg, String personality, String notes) {

    /** 是否为空补丁（无任何待更新字段）。 */
    public boolean isEmpty() {
        return petType == null && breed == null && gender == null && birthday == null
                && weightKg == null && personality == null && notes == null;
    }
}
