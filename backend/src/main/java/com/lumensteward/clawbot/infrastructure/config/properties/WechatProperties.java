package com.lumensteward.clawbot.infrastructure.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 微信通道配置（{@code wx.*}，G-16 收敛于 config 包；架构 5.4）。
 *
 * <p>记录（record）+ 构造器绑定，所有分量带 {@link DefaultValue}，缺省即可启动，
 * 占位符/缺失由 {@code PlaceholderConfigValidator}（SUP-06）在启动期统一校验。
 *
 * @param token             微信服务器 Token（BR-01，经环境变量注入，禁止明文）
 * @param appId             公众号/应用 AppId
 * @param appSecret         应用密钥（经环境变量注入）
 * @param encodingAesKey    消息加解密 EncodingAESKey（43 位 Base64；安全模式使用）
 * @param mockEnabled       微信通道 Mock 开关（SUP-01 / AC-D1/D2）：true=MOCK，false=REAL
 * @param timeWindowSeconds 回调签名时间窗（秒），防重放
 * @param dedupTtlSeconds   MsgId 去重集合 TTL（秒），REDIS dedup:msg:
 */
@ConfigurationProperties(prefix = "wx")
public record WechatProperties(
        @DefaultValue("") String token,
        @DefaultValue("") String appId,
        @DefaultValue("") String appSecret,
        @DefaultValue("") String encodingAesKey,
        @DefaultValue("true") boolean mockEnabled,
        @DefaultValue("300") int timeWindowSeconds,
        @DefaultValue("300") int dedupTtlSeconds) {
}
