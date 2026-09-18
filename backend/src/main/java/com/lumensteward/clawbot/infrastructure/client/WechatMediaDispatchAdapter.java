package com.lumensteward.clawbot.infrastructure.client;

import com.lumensteward.clawbot.domain.port.MediaDispatchException;
import com.lumensteward.clawbot.domain.port.MediaDispatchPort;
import com.lumensteward.clawbot.domain.port.model.MediaReference;
import com.lumensteward.clawbot.infrastructure.client.wechat.WechatTransport;
import com.lumensteward.clawbot.infrastructure.client.wechat.model.CustomerMessage;
import com.lumensteward.clawbot.infrastructure.client.wechat.model.MediaId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 微信媒体下发适配器（SRS FR-11 出站）。
 *
 * <p>实现领域端口 {@link MediaDispatchPort}：复用 {@link WechatTransport} 的素材上传与客服消息下发，
 * 隔离微信基础设施细节，保持领域层不反向依赖基础设施。
 */
@Component
public class WechatMediaDispatchAdapter implements MediaDispatchPort {

    private static final Logger log = LoggerFactory.getLogger(WechatMediaDispatchAdapter.class);

    private final WechatTransport transport;

    /**
     * 构造器注入（G-14）。
     *
     * @param transport 微信出站通道（Mock / Real 由 {@code wx.mock-enabled} 切换）
     */
    public WechatMediaDispatchAdapter(WechatTransport transport) {
        this.transport = transport;
    }

    @Override
    public MediaReference sendVoice(String openid, byte[] audioBytes, String filename)
            throws MediaDispatchException {
        if (openid == null || openid.isBlank()) {
            throw new MediaDispatchException("缺少接收用户 openid");
        }
        try {
            MediaId mediaId = transport.uploadTempMedia(audioBytes, "voice", filename);
            if (mediaId == null || mediaId.mediaId() == null) {
                throw new MediaDispatchException("语音素材上传失败");
            }
            transport.sendCustomerMessage(openid, CustomerMessage.voice(mediaId.mediaId()));
            log.info("语音已下发 openid={} mediaId={}", openid, mediaId.mediaId());
            return new MediaReference(mediaId.mediaId(), "voice");
        } catch (MediaDispatchException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("语音下发失败: {}", e.getMessage());
            throw new MediaDispatchException("语音下发失败", e);
        }
    }
}
