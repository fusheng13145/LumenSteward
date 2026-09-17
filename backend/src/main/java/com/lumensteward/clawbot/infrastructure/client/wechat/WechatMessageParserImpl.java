package com.lumensteward.clawbot.infrastructure.client.wechat;

import com.lumensteward.clawbot.infrastructure.client.wechat.model.InternalMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;

/**
 * {@link WechatMessageParser} 实现（SRS 9.4.6(2)，NFR-SE-07 XXE 防护）。
 *
 * <p>强制安全解析：禁用 DOCTYPE、禁用外部通用/参数实体、关闭 XInclude 与实体展开引用、
 * 启用安全处理特性。解析失败或报文为空时返回携带类型提示的空消息（不抛出，避免 500，
 * AC-A6）。
 */
@Component
public class WechatMessageParserImpl implements WechatMessageParser {

    private static final Logger log = LoggerFactory.getLogger(WechatMessageParserImpl.class);

    @Override
    public InternalMessage parse(String rawBody, String msgTypeHint) {
        if (rawBody == null || rawBody.isBlank()) {
            return new InternalMessage(null, normalizeType(msgTypeHint), null, null, null,
                    null, null, 0L, Map.of());
        }
        try {
            DocumentBuilderFactory factory = secureFactory();
            DocumentBuilder builder = factory.newDocumentBuilder();
            // 屏蔽外部实体解析器，进一步阻断 SSRF/文件读取
            builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
            Document document = builder.parse(new InputSource(new StringReader(rawBody)));
            Element root = document.getDocumentElement();
            if (root == null) {
                return new InternalMessage(null, normalizeType(msgTypeHint), null, null, null,
                        null, null, 0L, Map.of());
            }
            String openid = text(root, "FromUserName");
            String msgType = firstNonBlank(text(root, "MsgType"), msgTypeHint);
            String msgId = text(root, "MsgId");
            String content = text(root, "Content");
            String mediaId = text(root, "MediaId");
            Double latitude = parseDouble(text(root, "Location_X"));
            Double longitude = parseDouble(text(root, "Location_Y"));
            long createTime = parseLong(text(root, "CreateTime"));

            Map<String, String> eventAttributes = new HashMap<>();
            String event = text(root, "Event");
            if (event != null) {
                eventAttributes.put("Event", event);
            }
            String eventKey = text(root, "EventKey");
            if (eventKey != null) {
                eventAttributes.put("EventKey", eventKey);
            }
            return new InternalMessage(openid, normalizeType(msgType), msgId, content, mediaId,
                    latitude, longitude, createTime, eventAttributes);
        } catch (Exception e) {
            // 解析失败不抛出：返回带类型提示的空消息，交由上层走默认/兜底（AC-A6 不 500）
            log.warn("微信报文解析失败，回落为空消息: {}", e.getMessage());
            return new InternalMessage(null, normalizeType(msgTypeHint), null, null, null,
                    null, null, 0L, Map.of());
        }
    }

    private static DocumentBuilderFactory secureFactory() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory;
    }

    private static String text(Element root, String tag) {
        NodeList nodes = root.getElementsByTagName(tag);
        if (nodes.getLength() == 0) {
            return null;
        }
        String value = nodes.item(0).getTextContent();
        return value == null ? null : value.trim();
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second;
    }

    private static String normalizeType(String msgType) {
        return (msgType == null || msgType.isBlank()) ? "text" : msgType.trim().toLowerCase();
    }

    private static Double parseDouble(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Double.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
