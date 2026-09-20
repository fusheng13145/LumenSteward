package com.lumensteward.clawbot.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 四层异常事件实体，映射 {@code log_anomaly_event}（SRS 2.3.5 / A-3 四层分布，迭代 4 W1）。
 *
 * <p>承载此前不可见的 <b>L1 接入层 / L2 认知层</b>异常：{@code log_tool_call} 天然只记工具层
 * 调用，故 A-3 看板四层分布在 L1/L2 恒为 0；本表把两类判据（验签失败、时间戳越界、幂等重复、
 * 未知消息类型、LLM 超时/不可用/输出非法）落成可查事实。
 *
 * <p>{@code layer} 为埋点处<b>显式</b>给定的层次，不靠错误码前缀推断；{@code openid} 仅存
 * <b>脱敏形态</b>（BR-21）；表只增不改，{@code created_at} 由 {@code MyMetaObjectHandler} 填充。
 */
@Data
@TableName("log_anomaly_event")
public class AnomalyEventEntity {

    /** 主键。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 异常层次（L1/L2/L3/L4）。 */
    @TableField("layer")
    private String layer;

    /** 异常码。 */
    @TableField("error_code")
    private String errorCode;

    /** 埋点来源组件。 */
    @TableField("source")
    private String source;

    /** 链路标识（可空）。 */
    @TableField("trace_id")
    private String traceId;

    /** 脱敏后的用户标识（可空）。 */
    @TableField("openid")
    private String openid;

    /** 异常摘要（截断后，不含 PII）。 */
    @TableField("detail")
    private String detail;

    /** 创建时间（自动填充）。 */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
