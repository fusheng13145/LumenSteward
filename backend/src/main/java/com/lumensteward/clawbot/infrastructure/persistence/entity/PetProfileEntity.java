package com.lumensteward.clawbot.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 宠物档案实体，映射表 {@code biz_pet_profile}（SRS 7.2 表 7-4，表名按 7.6.1 校准）。
 *
 * <p><b>重要（架构 3.3）：</b>该表含数据库生成列 {@code live_marker}
 * （{@code TINYINT GENERATED ALWAYS AS (IF(deleted_at IS NULL, 1, NULL)) STORED}），
 * 用于支撑 {@code uk_openid_pet_name_live_marker} 复合唯一约束。生成列<b>不得</b>在本实体声明，
 * 否则 INSERT/UPDATE 会因写入生成列而报错——故本类<b>刻意不含</b> {@code liveMarker} 字段。
 *
 * <p>软删除采用 {@code deleted_at}（NULL 表示未删除），由 {@link TableLogic} 声明。
 */
@Data
@TableName("biz_pet_profile")
public class PetProfileEntity {

    /** 主键。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 所属用户。 */
    @TableField("openid")
    private String openid;

    /** 宠物昵称（必填）。 */
    @TableField("pet_name")
    private String petName;

    /** 宠物类型：猫/狗/其他。 */
    @TableField("pet_type")
    private String petType;

    /** 品种。 */
    @TableField("breed")
    private String breed;

    /** 性别：公/母/未知。 */
    @TableField("gender")
    private String gender;

    /** 生日（须 ≤ 今日）。 */
    @TableField("birthday")
    private LocalDate birthday;

    /** 体重（kg），可空。 */
    @TableField("weight_kg")
    private BigDecimal weightKg;

    /** 性格描述。 */
    @TableField("personality")
    private String personality;

    /** 备注。 */
    @TableField("notes")
    private String notes;

    /** 头像素材 ID。 */
    @TableField("photo_media_id")
    private String photoMediaId;

    /** 创建时间（自动填充）。 */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 更新时间（自动填充）。 */
    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /** 软删除时间（NULL 表示未删除）。 */
    @TableLogic(value = "null", delval = "now()")
    @TableField("deleted_at")
    private LocalDateTime deletedAt;
}
