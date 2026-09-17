package com.lumensteward.clawbot.application.dispatcher.handler;

import com.lumensteward.clawbot.application.dispatcher.MessageHandler;
import com.lumensteward.clawbot.infrastructure.client.wechat.model.InternalMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 位置消息占位处理器（FR-02 / FR-13）。
 *
 * <p>返回如实提示；<b>不</b>落库原始坐标（BR-17：位置信息仅临时使用）。
 */
@Component
public class LocationMessageHandler implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(LocationMessageHandler.class);

    @Override
    public String supportsMsgType() {
        return "location";
    }

    @Override
    public String handle(InternalMessage message) {
        log.info("收到位置消息（占位处理，不落库坐标）");
        return "我收到你的位置啦，不过我暂时还不能用它做导航，稍后会支持的。";
    }
}
