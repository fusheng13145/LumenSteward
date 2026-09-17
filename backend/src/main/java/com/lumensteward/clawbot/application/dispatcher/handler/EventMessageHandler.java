package com.lumensteward.clawbot.application.dispatcher.handler;

import com.lumensteward.clawbot.application.dispatcher.MessageHandler;
import com.lumensteward.clawbot.infrastructure.client.wechat.model.InternalMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 事件消息处理器（FR-02 备选流 3b）。
 *
 * <p>{@code subscribe}/{@code unsubscribe}/菜单点击等事件<b>不进入对话引擎</b>，由会话状态管理
 * 直接处理：关注 → 欢迎语；取关 → 静默；其余事件 → 静默。
 */
@Component
public class EventMessageHandler implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(EventMessageHandler.class);

    @Override
    public String supportsMsgType() {
        return "event";
    }

    @Override
    public String handle(InternalMessage message) {
        String event = message == null ? null : message.event();
        log.info("收到事件消息 event={}", event);
        if ("subscribe".equalsIgnoreCase(event)) {
            return "你好呀，我是衔光管家～有任何想聊的、想记的，都可以直接告诉我。";
        }
        // unsubscribe 及其他事件：静默处理，不进入对话引擎
        return null;
    }
}
