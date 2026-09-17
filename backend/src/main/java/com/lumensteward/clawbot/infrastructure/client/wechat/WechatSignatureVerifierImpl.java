package com.lumensteward.clawbot.infrastructure.client.wechat;

import com.lumensteward.clawbot.common.exception.SignatureInvalidException;
import com.lumensteward.clawbot.common.exception.TimestampOutOfWindowException;
import com.lumensteward.clawbot.infrastructure.config.properties.WechatProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * {@link WechatSignatureVerifier} 实现（SRS 9.4.6 / BR-01）。
 *
 * <p>算法：将 token、timestamp、nonce 三个参数按字典序排序后拼接为字符串，做 sha1 取十六进制，
 * 与平台签名比对；随后校验 timestamp 与当前时间偏差不超过 {@code wx.time-window-seconds}（默认
 * 300s）。任一不满足即拒绝（AC-A2 / AC-A5）。
 */
@Component
public class WechatSignatureVerifierImpl implements WechatSignatureVerifier {

    private static final Logger log = LoggerFactory.getLogger(WechatSignatureVerifierImpl.class);

    private final WechatProperties properties;

    /**
     * 构造器注入（G-14）。
     *
     * @param properties 微信配置（含 token 与时间窗）
     */
    public WechatSignatureVerifierImpl(WechatProperties properties) {
        this.properties = properties;
    }

    @Override
    public void verify(String signature, String timestamp, String nonce, String encrypt) {
        if (signature == null || timestamp == null || nonce == null) {
            throw new SignatureInvalidException("签名参数缺失");
        }
        // 1) 时间窗校验（防重放）
        checkTimeWindow(timestamp);

        // 2) 签名比对
        String expected = sha1(sortAndConcat(properties.token(), timestamp, nonce));
        if (!expected.equalsIgnoreCase(signature)) {
            log.warn("微信签名校验失败（不一致）");
            throw new SignatureInvalidException("微信签名校验失败");
        }
    }

    /**
     * 生成签名（同时供本地签名工具与测试复用）。
     *
     * @param token     微信 Token
     * @param timestamp 时间戳
     * @param nonce     随机串
     * @return 十六进制 sha1 签名
     */
    public static String computeSignature(String token, String timestamp, String nonce) {
        return sha1(sortAndConcat(token, timestamp, nonce));
    }

    private void checkTimeWindow(String timestamp) {
        long ts;
        try {
            ts = Long.parseLong(timestamp.trim());
        } catch (NumberFormatException e) {
            throw new TimestampOutOfWindowException("时间戳格式非法");
        }
        long nowSeconds = System.currentTimeMillis() / 1000L;
        long window = Math.max(1, properties.timeWindowSeconds());
        if (Math.abs(nowSeconds - ts) > window) {
            log.warn("微信回调时间戳超出窗口: diff={}s window={}s", Math.abs(nowSeconds - ts), window);
            throw new TimestampOutOfWindowException("请求时间戳超出允许窗口");
        }
    }

    private static String sortAndConcat(String token, String timestamp, String nonce) {
        String[] arr = {token == null ? "" : token, timestamp == null ? "" : timestamp,
                nonce == null ? "" : nonce};
        Arrays.sort(arr);
        return String.join("", arr);
    }

    private static String sha1(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 不可用", e);
        }
    }
}
