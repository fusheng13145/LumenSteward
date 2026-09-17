package com.lumensteward.clawbot.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 消息实体，映射表 {@code wx_message}（SRS 7.2 表 7-3，表名按 7.6.1 校准）。
 *
 * <p>消息「只增不改」，故仅有 {@code created_at}（自动填充），无 {@code updated_at}。
 */
@Data
@TableName("wx_message")
public class WxMessageEntity {

    /** 主键。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 所属会话（逻辑外键 → wx_session.id）。 */
    @TableField("session_id")
    private Long sessionId;

    /** 冗余用户标识，便于按用户查询与删除。 */
    @TableField("openid")
    private String openid;

    /** 微信 MsgId（幂等追溯用）。 */
    @TableField("msg_id")
    private String msgId;

    /** 角色：user/assistant/tool（与 {@code MessageRole} 同源）。 */
    @TableField("role")
    private String role;

    /** 消息类型：text/image/voice/location/event（与 {@code MessageType} 同源）。 */
    @TableField("msg_type")
    private String msgType;

    /** 消息内容（个人信息载体）。 */
    @TableField("content")
    private String content;

    /** 关联素材标识。 */
    @TableField("media_id")
    private String mediaId;

    /** 若 role=tool，记录工具名。 */
    @TableField("tool_name")
    private String toolName;

    /** 估算 token 数（用于上下文裁剪）。 */
    @TableField("token_count")
    private Integer tokenCount;

    /** 发送状态：0-待发 1-成功 2-失败。 */
    @TableField("send_status")
    private Integer sendStatus;

    /** 创建时间（自动填充）。 */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
