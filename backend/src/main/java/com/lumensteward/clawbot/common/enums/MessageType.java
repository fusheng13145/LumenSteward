package com.lumensteward.clawbot.common.enums;

import lombok.Getter;

import java.util.Optional;

/**
 * 消息类型（对齐 {@code wx_message.msg_type} 字段 COMMENT，7.6.3；亦为消息路由的 supportsMsgType 依据，FR-02）。
 *
 * <p>与前端 {@code src/utils/constants.ts} 中 MESSAGE_TYPE 同源。
 */
@Getter
public enum MessageType {

    /** 文本。 */
    TEXT("text"),
    /** 图片。 */
    IMAGE("image"),
    /** 语音。 */
    VOICE("voice"),
    /** 位置。 */
    LOCATION("location"),
    /** 事件（订阅/菜单等）。 */
    EVENT("event");

    /** 平台线值。 */
    private final String wire;

    MessageType(String wire) {
        this.wire = wire;
    }

    /**
     * 按平台线值查找类型。
     *
     * @param wire 线值（text/image/voice/location/event）
     * @return 匹配的类型
     */
    public static Optional<MessageType> fromWire(String wire) {
        if (wire == null) {
            return Optional.empty();
        }
        for (MessageType type : values()) {
            if (type.wire.equalsIgnoreCase(wire)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
