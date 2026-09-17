package com.lumensteward.clawbot.infrastructure.client.wechat.model;

import java.util.Map;

/**
 * 内部消息模型（架构 5.1 / SRS 9.4.6(2)）。
 *
 * <p><b>G-21 硬约束：</b>本模型<b>不得</b>出现任何平台专有字段名（如 {@code FromUserName}、
 * {@code ToUserName}）——平台报文差异仅在解析层消化，业务层只面对本模型。
 *
 * @param openid          用户标识（脱敏后日志输出）
 * @param msgType         消息类型（text/image/voice/location/event，已归一为线值）
 * @param msgId           平台消息 id（幂等去重键）
 * @param content         文本内容（仅 text）
 * @param mediaId         素材标识（image/voice）
 * @param latitude        纬度（location）
 * @param longitude       经度（location）
 * @param createTime      平台时间戳（秒）
 * @param eventAttributes 事件属性（event：subscribe/unsubscribe/CLICK 等）
 */
public record InternalMessage(String openid, String msgType, String msgId, String content,
                              String mediaId, Double latitude, Double longitude, long createTime,
                              Map<String, String> eventAttributes) {

    public InternalMessage {
        eventAttributes = eventAttributes == null ? Map.of() : Map.copyOf(eventAttributes);
    }

    /** 是否为文本消息。 */
    public boolean isText() {
        return "text".equalsIgnoreCase(msgType);
    }

    /** 事件类型（仅 event 消息有意义）。 */
    public String event() {
        return eventAttributes.get("Event");
    }
}
