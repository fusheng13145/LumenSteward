package com.lumensteward.clawbot.application.dispatcher.handler;

import com.lumensteward.clawbot.application.dispatcher.MessageHandler;
import com.lumensteward.clawbot.infrastructure.client.wechat.model.InternalMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 语音消息占位处理器（FR-02 / FR-11）。
 *
 * <p>返回如实提示（BR-14：语音消息默认附文字回执），不发起 ASR/TTS 外呼。
 */
@Component
public class VoiceMessageHandler implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(VoiceMessageHandler.class);

    @Override
    public String supportsMsgType() {
        return "voice";
    }

    @Override
    public String handle(InternalMessage message) {
        log.info("收到语音消息（占位处理，不外呼）");
        return "我暂时还不能听懂语音哦，方便的话发文字给我吧～";
    }
}
