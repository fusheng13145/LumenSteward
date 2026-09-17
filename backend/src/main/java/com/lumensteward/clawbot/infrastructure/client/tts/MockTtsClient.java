package com.lumensteward.clawbot.infrastructure.client.tts;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 语音合成 Mock 实现（SRS FR-11，AC-D1：无外网）。
 *
 * <p>返回确定性字节序列（非真实音频），供联调断言链路；真实 TTS 在后续迭代接入。
 */
@Component
public class MockTtsClient implements TtsClient {

    private static final Logger log = LoggerFactory.getLogger(MockTtsClient.class);

    @Override
    public byte[] synthesize(String text, String voiceId) {
        if (text == null || text.isBlank()) {
            return new byte[0];
        }
        log.info("Mock 语音合成 voiceId={} textLength={}", voiceId, text.length());
        return ("mock-audio:" + text).getBytes(StandardCharsets.UTF_8);
    }
}
