package com.lumensteward.clawbot.wechat;

import com.lumensteward.clawbot.infrastructure.client.wechat.WechatMessageParserImpl;
import com.lumensteward.clawbot.infrastructure.client.wechat.model.InternalMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 微信报文解析测试（G-21：禁 XXE；内部模型无平台字段）。
 */
class WechatMessageParserTest {

    private final WechatMessageParserImpl parser = new WechatMessageParserImpl();

    @Test
    @DisplayName("明文 XML 正常解析为内部模型")
    void shouldParsePlainXml() {
        String xml = "<xml><FromUserName><![CDATA[openid-1234]]></FromUserName>"
                + "<ToUserName><![CDATA[gh_xxx]]></ToUserName>"
                + "<MsgType><![CDATA[text]]></MsgType><MsgId>101</MsgId>"
                + "<Content><![CDATA[你好]]></Content><CreateTime>1700000000</CreateTime></xml>";
        InternalMessage message = parser.parse(xml, null);
        assertThat(message.openid()).isEqualTo("openid-1234");
        assertThat(message.msgType()).isEqualTo("text");
        assertThat(message.msgId()).isEqualTo("101");
        assertThat(message.content()).isEqualTo("你好");
        assertThat(message.createTime()).isEqualTo(1700000000L);
    }

    @Test
    @DisplayName("XXE 防护：DOCTYPE/外部实体被禁，不泄露文件内容")
    void shouldRejectXxe() {
        String xxe = "<?xml version=\"1.0\"?>"
                + "<!DOCTYPE foo [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>"
                + "<xml><FromUserName>&xxe;</FromUserName><MsgType>text</MsgType></xml>";
        InternalMessage message = parser.parse(xxe, "text");
        // 解析被安全阻断：openid 不应为实体展开内容
        assertThat(message.openid()).isNull();
        assertThat(message.msgType()).isEqualTo("text");
    }

    @Test
    @DisplayName("空报文返回携带类型提示的空消息（不抛出）")
    void shouldHandleBlankBody() {
        InternalMessage message = parser.parse("", "image");
        assertThat(message.msgType()).isEqualTo("image");
        assertThat(message.openid()).isNull();
    }

    @Test
    @DisplayName("解析失败不抛出，回落空消息（避免 500）")
    void shouldNotThrowOnMalformed() {
        InternalMessage message = parser.parse("<xml><broken", "text");
        assertThat(message).isNotNull();
        assertThat(message.msgType()).isEqualTo("text");
    }
}
