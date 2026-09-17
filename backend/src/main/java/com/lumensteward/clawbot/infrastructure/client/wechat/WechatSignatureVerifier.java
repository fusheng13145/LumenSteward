package com.lumensteward.clawbot.infrastructure.client.wechat;

/**
 * 微信回调签名校验器（架构 5.1 / SRS 9.4.6，BR-01）。
 *
 * <p><b>硬约束：</b>无论 Mock 还是 Real 通道，验签<b>始终真实执行</b>，且 GET 校验与 POST 校验
 * 复用同一段代码（单一入口）。
 */
public interface WechatSignatureVerifier {

    /**
     * 校验签名与时间窗。
     *
     * @param signature 平台签名（sha1）
     * @param timestamp 平台时间戳（秒）
     * @param nonce     随机串
     * @param encrypt   密文（安全模式，可为空）
     * @throws com.lumensteward.clawbot.common.exception.SignatureInvalidException      签名不一致
     * @throws com.lumensteward.clawbot.common.exception.TimestampOutOfWindowException  时间戳越界
     */
    void verify(String signature, String timestamp, String nonce, String encrypt);
}
