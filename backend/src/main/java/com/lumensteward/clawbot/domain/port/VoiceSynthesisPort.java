package com.lumensteward.clawbot.domain.port;

/**
 * 语音合成能力端口（SRS FR-11）。
 *
 * <p>上提自 {@code infrastructure/client/tts}，由 {@code MockTtsClient} / {@code RealTtsClient}
 * 实现（TODO-03）。
 */
public interface VoiceSynthesisPort {

    /**
     * 文本转语音。
     *
     * @param text    文本（≤ 250 字，由调用方校验）
     * @param voiceId 音色标识（可空，使用默认音色）
     * @return 音频字节；失败抛出 {@link VoiceSynthesisException}
     * @throws VoiceSynthesisException 合成失败
     */
    byte[] synthesize(String text, String voiceId) throws VoiceSynthesisException;
}
