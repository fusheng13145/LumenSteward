package com.lumensteward.clawbot.infrastructure.client.wechat;

import com.lumensteward.clawbot.common.util.MaskUtils;
import com.lumensteward.clawbot.infrastructure.client.wechat.model.CustomerMessage;
import com.lumensteward.clawbot.infrastructure.client.wechat.model.MediaId;
import com.lumensteward.clawbot.infrastructure.client.wechat.model.SendResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Mock 微信出站通道（架构 5.1 / SRS 9.4.6，AC-D1：无外网）。
 *
 * <p>当 {@code wx.mock-enabled=true}（默认）时装配。所有出站动作均不产生网络调用，仅记录
 * {@code send_status}（成功/失败 + 耗时），供联调与测试断言。
 */
@Component
@ConditionalOnProperty(name = "wx.mock-enabled", havingValue = "true", matchIfMissing = true)
public class MockWechatTransport implements WechatTransport {

    private static final Logger log = LoggerFactory.getLogger(MockWechatTransport.class);

    public static final String MODE = "mock";

    @Override
    public String transportMode() {
        return MODE;
    }

    @Override
    public SendResult sendCustomerMessage(String openid, CustomerMessage message) {
        long start = System.currentTimeMillis();
        // 记录 send_status：Mock 通道恒定成功（0），便于端到端断言
        log.info("Mock 出站客服消息 send_status=0(成功) openid={} msgType={} latency={}ms",
                MaskUtils.openid(openid), message.msgType(), System.currentTimeMillis() - start);
        return SendResult.ok(System.currentTimeMillis() - start);
    }

    @Override
    public MediaId uploadTempMedia(byte[] bytes, String type, String filename) {
        String mediaId = "mock-media-" + (type == null ? "bin" : type) + "-" + UUID.randomUUID();
        log.info("Mock 上传素材 type={} filename={} bytes={} mediaId={}",
                type, filename, bytes == null ? 0 : bytes.length, mediaId);
        return MediaId.of(mediaId, type);
    }

    @Override
    public byte[] downloadMedia(String mediaId) {
        log.info("Mock 下载素材 mediaId={}", mediaId);
        return ("mock-media-body:" + mediaId).getBytes(StandardCharsets.UTF_8);
    }
}
