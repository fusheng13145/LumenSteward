package com.lumensteward.clawbot.infrastructure.client.wechat;

import com.lumensteward.clawbot.infrastructure.client.wechat.model.InternalMessage;

/**
 * 微信报文解析器（架构 5.1 / SRS 9.4.6(2)）。
 *
 * <p>平台报文（明文 XML）→ 内部消息模型；<b>必须</b>采用安全解析并禁用外部实体（XXE 防护，
 * NFR-SE-07）；通道差异仅在本层消化（G-21）。
 */
public interface WechatMessageParser {

    /**
     * 解析报文。
     *
     * @param rawBody     原始请求体
     * @param msgTypeHint 类型提示（可空；解析层未取到 MsgType 时回退使用）
     * @return 内部消息模型（绝不返回 null）
     */
    InternalMessage parse(String rawBody, String msgTypeHint);
}
