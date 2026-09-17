package com.lumensteward.clawbot.application.dispatcher;

import com.lumensteward.clawbot.common.util.MaskUtils;
import com.lumensteward.clawbot.infrastructure.client.wechat.model.InternalMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 消息分发器（架构 5.2 / SRS 9.4.6(3)，BR-05 单一入口）。
 *
 * <p>微信回调只暴露一个端点，内部按消息类型分派到 {@link MessageHandler} 策略族。未知类型
 * <b>不</b>抛异常，回落默认文本处理（SRS 2.3.5 L1：记录日志走默认文本处理，AC-A6 不 500）。
 */
@Component
public class MessageDispatcher {

    private static final Logger log = LoggerFactory.getLogger(MessageDispatcher.class);

    /** 默认类型（未知类型回落）。 */
    private static final String DEFAULT_TYPE = "text";

    private final Map<String, MessageHandler> handlers;

    /**
     * 构造器注入策略族（G-14 / FR-23 同构的插件化）。
     *
     * @param handlerList Spring 容器内全部处理器
     */
    public MessageDispatcher(List<MessageHandler> handlerList) {
        Map<String, MessageHandler> map = new LinkedHashMap<>();
        if (handlerList != null) {
            for (MessageHandler handler : handlerList) {
                String key = normalize(handler.supportsMsgType());
                MessageHandler previous = map.putIfAbsent(key, handler);
                if (previous != null) {
                    throw new IllegalStateException("消息处理器类型冲突: " + key);
                }
            }
        }
        this.handlers = Collections.unmodifiableMap(map);
        log.info("MessageDispatcher 初始化完成，已注册类型={}", this.handlers.keySet());
    }

    /**
     * 分发消息（BR-05 单一入口）。
     *
     * @param message 内部消息
     * @return 回复文本；null/空表示无需被动回复
     */
    public String dispatch(InternalMessage message) {
        if (message == null) {
            log.warn("分发空消息，忽略");
            return null;
        }
        String type = normalize(message.msgType());
        MessageHandler handler = handlers.get(type);
        if (handler == null) {
            log.warn("未知消息类型 type={} openid={}，回落默认文本处理（不 500）",
                    type, MaskUtils.openid(message.openid()));
            handler = handlers.get(DEFAULT_TYPE);
        }
        if (handler == null) {
            log.error("无可用处理器（含默认），返回空回复");
            return null;
        }
        try {
            return handler.handle(message);
        } catch (RuntimeException e) {
            // 处理器异常不得冒泡为 500（L1/L2 兜底由上层保证）
            log.error("消息处理异常 type={} err={}", type, e.getMessage(), e);
            return null;
        }
    }

    /** 已注册的消息类型集合。 */
    public Set<String> supportedTypes() {
        return handlers.keySet();
    }

    private static String normalize(String type) {
        return type == null || type.isBlank() ? DEFAULT_TYPE : type.trim().toLowerCase(Locale.ROOT);
    }
}
