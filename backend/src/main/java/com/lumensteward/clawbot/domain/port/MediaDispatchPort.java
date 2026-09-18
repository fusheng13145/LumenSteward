package com.lumensteward.clawbot.domain.port;

import com.lumensteward.clawbot.domain.port.model.MediaReference;

/**
 * 媒体下发能力端口（SRS FR-11 出站）。
 *
 * <p>上提自 {@code infrastructure/client/wechat} 的素材上传+发送能力，使 {@code domain} 不直接
 * 依赖微信基础设施。由 {@code WechatMediaDispatchAdapter} 实现。
 */
public interface MediaDispatchPort {

    /**
     * 合成语音并下发至用户。
     *
     * @param openid     接收用户（日志须脱敏）
     * @param audioBytes 音频字节（建议已转码为微信支持的 amr/speex）
     * @param filename   文件名（含扩展名）
     * @return 媒体引用（素材 id 等）
     * @throws MediaDispatchException 上传或下发失败
     */
    MediaReference sendVoice(String openid, byte[] audioBytes, String filename) throws MediaDispatchException;
}
