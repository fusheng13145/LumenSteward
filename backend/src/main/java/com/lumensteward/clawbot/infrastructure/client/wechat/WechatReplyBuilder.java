package com.lumensteward.clawbot.infrastructure.client.wechat;

import com.lumensteward.clawbot.infrastructure.client.wechat.model.InternalMessage;

/**
 * 微信回复构造器（架构 5.1 / SRS 9.4.6(2) 回复层）。
 *
 * <p>内部回复模型 → 平台报文；类型映射与长度截断在此完成（平台专有字段名仅存在于本层，
 * 不得渗透入内部模型，G-21）。
 */
public interface WechatReplyBuilder {

    /**
     * 构造文本被动回复报文。
     *
     * @param inbound 入站消息
     * @param text    回复文本
     * @return 平台 XML 报文
     */
    String buildTextReply(InternalMessage inbound, String text);
}
