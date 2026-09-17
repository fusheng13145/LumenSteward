package com.lumensteward.clawbot.application.dispatcher.handler;

import com.lumensteward.clawbot.application.dispatcher.MessageHandler;
import com.lumensteward.clawbot.infrastructure.client.wechat.model.InternalMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 图片消息占位处理器（FR-02 / FR-10）。
 *
 * <p>返回如实提示，且<b>不</b>发起任何视觉/LLM 调用（AC-A9/A10：返回如实提示且不外呼；
 * BR-09：识别结果不可臆造）。
 */
@Component
public class ImageMessageHandler implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(ImageMessageHandler.class);

    @Override
    public String supportsMsgType() {
        return "image";
    }

    @Override
    public String handle(InternalMessage message) {
        log.info("收到图片消息（占位处理，不外呼）");
        return "我暂时还不能帮你识别图片哦，可以先告诉我这是什么吗？";
    }
}
