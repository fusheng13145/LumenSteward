package com.lumensteward.clawbot.application.dispatcher.handler;

import com.lumensteward.clawbot.application.dispatcher.MessageHandler;
import com.lumensteward.clawbot.infrastructure.client.wechat.model.InternalMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 文本消息处理器（FR-02）。
 *
 * <p>T03 仅打通接入链路（路由 + 落库 + 回执），不应答具体业务结论；对话编排在 T04 接入本类
 * （注入 {@code AgentOrchestrator} 后返回终态回复）。此处返回 null 表示"无被动正文"，
 * 由控制器回 {@code success} 回执，符合"不编造、不 500"的诚实原则（BR-04）。
 */
@Component
public class TextMessageHandler implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(TextMessageHandler.class);

    @Override
    public String supportsMsgType() {
        return "text";
    }

    @Override
    public String handle(InternalMessage message) {
        log.debug("文本消息进入处理（接入层），openid 已脱敏");
        // T04 将在此调用 AgentOrchestrator 产出终态回复
        return null;
    }
}
