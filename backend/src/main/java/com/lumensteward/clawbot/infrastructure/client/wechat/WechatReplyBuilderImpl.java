package com.lumensteward.clawbot.infrastructure.client.wechat;

import com.lumensteward.clawbot.infrastructure.client.wechat.model.InternalMessage;
import com.lumensteward.clawbot.infrastructure.config.properties.WechatProperties;
import org.springframework.stereotype.Component;

/**
 * {@link WechatReplyBuilder} 实现（SRS 9.4.6(2) 回复层）。
 *
 * <p>输出平台文本被动回复 XML；内容以 CDATA 包裹，并处理 {@code ]]>} 边界转义。发送方标识优先
 * 取配置的 {@code appId}，缺省用中性占位（不泄露内部信息）。平台字段名（ToUserName/FromUserName
 * 等）仅存在于本层。
 */
@Component
public class WechatReplyBuilderImpl implements WechatReplyBuilder {

    /** 平台文本回复的最大长度（保守截断，避免超限被拒）。 */
    private static final int MAX_CONTENT_LENGTH = 2000;

    private final String fromUserName;

    /**
     * 构造器注入（G-14）。
     *
     * @param properties 微信配置
     */
    public WechatReplyBuilderImpl(WechatProperties properties) {
        this.fromUserName = (properties.appId() == null || properties.appId().isBlank())
                ? "officialAccount" : properties.appId();
    }

    @Override
    public String buildTextReply(InternalMessage inbound, String text) {
        String toUserName = inbound == null || inbound.openid() == null ? "" : inbound.openid();
        String content = text == null ? "" : text;
        if (content.length() > MAX_CONTENT_LENGTH) {
            content = content.substring(0, MAX_CONTENT_LENGTH);
        }
        long createTime = System.currentTimeMillis() / 1000L;
        return "<xml>"
                + "<ToUserName><![CDATA[" + cdata(toUserName) + "]]></ToUserName>"
                + "<FromUserName><![CDATA[" + cdata(fromUserName) + "]]></FromUserName>"
                + "<CreateTime>" + createTime + "</CreateTime>"
                + "<MsgType><![CDATA[text]]></MsgType>"
                + "<Content><![CDATA[" + cdata(content) + "]]></Content>"
                + "</xml>";
    }

    private static String cdata(String value) {
        if (value == null) {
            return "";
        }
        // CDATA 段内不得出现 "]]>"，拆分转义
        return value.replace("]]>", "]]]]><![CDATA[>");
    }
}
