package com.lumensteward.clawbot.infrastructure.client.wechat;

import com.lumensteward.clawbot.infrastructure.client.wechat.model.CustomerMessage;
import com.lumensteward.clawbot.infrastructure.client.wechat.model.MediaId;
import com.lumensteward.clawbot.infrastructure.client.wechat.model.SendResult;

/**
 * 微信出站通道 SPI（架构 5.1 / SRS 9.4.6）。
 *
 * <p>按 {@code wx.mock-enabled} 在 {@link MockWechatTransport} / {@link RealWechatTransport}
 * 之间切换，业务层只依赖本接口（AC-D3 零代码改动）。<b>入站</b>（回调验签/解析）由
 * {@code WechatCallbackController} + 验证器/解析器承担，<b>不</b>经由本接口——从而保证无论
 * Mock 还是 Real，验签与解析逻辑完全一致（单一入口硬约束）。
 */
public interface WechatTransport {

    /**
     * 当前通道模式。
     *
     * @return {@code "mock"} 或 {@code "real"}
     */
    String transportMode();

    /**
     * 发送客服消息（出站）。
     *
     * @param openid  接收用户
     * @param message 消息
     * @return 发送结果
     */
    SendResult sendCustomerMessage(String openid, CustomerMessage message);

    /**
     * 上传临时素材。
     *
     * @param bytes    素材字节
     * @param type     素材类型（image/voice/video/thumb）
     * @param filename 文件名
     * @return 素材标识
     */
    MediaId uploadTempMedia(byte[] bytes, String type, String filename);

    /**
     * 下载素材。
     *
     * @param mediaId 素材标识
     * @return 素材字节
     */
    byte[] downloadMedia(String mediaId);
}
