package com.lumensteward.clawbot.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 个人状态库条目实体，映射表 {@code biz_memory_item}（迭代 4 W6 / §2.19）。
 *
 * <p><b>重要（架构 3.3）：</b>本表含数据库生成列 {@code live_marker}
 * （{@code IF(deleted_at IS NULL AND status = 'ACTIVE', 1, NULL)}），
 * 用于支撑 {@code uk_openid_kind_name_live_marker}：同一 (openid, kind, name) 的
 * <b>活记录严格唯一</b>，而被覆盖的历史行（status=SUPERSEDED）marker 为 NULL，可无限保留。
 * 生成列<b>不得</b>在本实体声明，否则写入报错——故本类刻意不含该字段。
 *
 * <p>{@code status} 由应用层显式维护（覆盖写时旧行转 SUPERSEDED），不参与 MyBatis-Plus 逻辑删除。
 */
@Data
@TableName("biz_memory_item")
public class MemoryItemEntity {

    /** 主键。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 所属用户（隔离键，BR-07）。 */
    @TableField("openid")
    private String openid;

    /** 条目类型：PERSON/PLACE/THING/PREFERENCE/HABIT/FACT。 */
    @TableField("kind")
    private String kind;

    /** 实体名 / 偏好键。 */
    @TableField("name")
    private String name;

    /** 事实正文。 */
    @TableField("content")
    private String content;

    /** 来源方式：AUTO_EXTRACT / TOOL。 */
    @TableField("origin")
    private String origin;

    /** 抽取器标识与版本。 */
    @TableField("extractor")
    private String extractor;

    /** 抽取置信度 0.000~1.000。 */
    @TableField("confidence")
    private BigDecimal confidence;

    /** 溯源：来源会话 id。 */
    @TableField("source_session_id")
    private Long sourceSessionId;

    /** 溯源：来源链路标识。 */
    @TableField("source_trace_id")
    private String sourceTraceId;

    /** 状态：ACTIVE / SUPERSEDED。 */
    @TableField("status")
    private String status;

    /** 本条覆盖掉的旧条 id。 */
    @TableField("supersedes_id")
    private Long supersedesId;

    /** 累计出现次数。 */
    @TableField("hit_count")
    private Integer hitCount;

    /** 首次出现时间。 */
    @TableField("first_seen_at")
    private LocalDateTime firstSeenAt;

    /** 最近出现时间。 */
    @TableField("last_seen_at")
    private LocalDateTime lastSeenAt;

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
