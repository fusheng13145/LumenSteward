package com.lumensteward.clawbot.infrastructure.client.wechat.model;

/**
 * 客服消息（出站，架构 5.1 / SRS FR-03）。
 *
 * <p>用于「先回执后推送」中的异步推送（BR-06）。
 *
 * @param msgType 消息类型：text / voice
 * @param content 文本内容（msgType=text 时使用）
 * @param mediaId 语音素材 id（msgType=voice 时使用）
 */
public record CustomerMessage(String msgType, String content, String mediaId) {

    public static final String TYPE_TEXT = "text";
    public static final String TYPE_VOICE = "voice";

    /** 文本客服消息。 */
    public static CustomerMessage text(String content) {
        return new CustomerMessage(TYPE_TEXT, content, null);
    }

    /** 语音客服消息。 */
    public static CustomerMessage voice(String mediaId) {
        return new CustomerMessage(TYPE_VOICE, null, mediaId);
    }
}
