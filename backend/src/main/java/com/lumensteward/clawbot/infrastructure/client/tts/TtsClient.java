package com.lumensteward.clawbot.infrastructure.client.tts;

/**
 * 语音合成 SPI（架构 5.1 / SRS FR-11）。
 *
 * <p><b>注意（BR-04）：</b>本接口及其 Mock 实现<b>不</b>实现 {@code Tool} 接口，因而不会被注册、
 * 也不会下发模型。
 */
public interface TtsClient {

    /**
     * 文本转语音。
     *
     * @param text    文本（≤ 250 字）
     * @param voiceId 音色标识（可空，用默认音色）
     * @return 音频字节；失败返回空数组
     */
    byte[] synthesize(String text, String voiceId);
}
