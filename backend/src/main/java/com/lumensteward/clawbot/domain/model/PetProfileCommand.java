package com.lumensteward.clawbot.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 宠物档案创建命令（架构 5.3 / SRS FR-14）。
 *
 * @param petName     宠物昵称（必填，≤ 32 字符，BR-02）
 * @param petType     类型（猫/狗/其他）
 * @param breed       品种
 * @param gender      性别（公/母/未知）
 * @param birthday    生日（≤ 今日）
 * @param weightKg    体重（kg）
 * @param personality 性格描述
 * @param notes       备注
 */
public record PetProfileCommand(String petName, String petType, String breed, String gender,
                                LocalDate birthday, BigDecimal weightKg, String personality,
                                String notes) {
}
