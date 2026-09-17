package com.lumensteward.clawbot.common.enums;

import lombok.Getter;

import java.util.Optional;

/**
 * 消息角色（对齐 {@code wx_message.role} 字段 COMMENT，7.6.3）。
 *
 * <p>取值与 OpenAI 兼容协议的 role 字段一致，便于上下文直接下发 LLM（9.4.1）。
 */
@Getter
public enum MessageRole {

    /** 用户消息。 */
    USER("user"),
    /** 助手消息。 */
    ASSISTANT("assistant"),
    /** 工具返回消息。 */
    TOOL("tool");

    /** 协议线值。 */
    private final String wire;

    MessageRole(String wire) {
        this.wire = wire;
    }

    /**
     * 按协议线值查找角色。
     *
     * @param wire 线值（user/assistant/tool）
     * @return 匹配的角色
     */
    public static Optional<MessageRole> fromWire(String wire) {
        if (wire == null) {
            return Optional.empty();
        }
        for (MessageRole role : values()) {
            if (role.wire.equalsIgnoreCase(wire)) {
                return Optional.of(role);
            }
        }
        return Optional.empty();
    }
}
