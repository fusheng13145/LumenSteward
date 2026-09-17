package com.lumensteward.clawbot.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

/**
 * 本地微信签名生成工具（测试支撑，架构 7.4 文件清单）。
 *
 * <p>独立于生产代码实现 sha1(sort(token,timestamp,nonce))，用于构造合法/非法签名，验证验签链路
 * （AC-A2 / AC-A5）。刻意不复用生产实现，以形成"独立参照"。
 */
public final class WechatSignatureGenerator {

    private WechatSignatureGenerator() {
    }

    /**
     * 计算签名。
     *
     * @param token     微信 Token
     * @param timestamp 时间戳
     * @param nonce     随机串
     * @return 十六进制 sha1
     */
    public static String sign(String token, String timestamp, String nonce) {
        String[] arr = {token, timestamp, nonce};
        Arrays.sort(arr);
        return sha1(String.join("", arr));
    }

    /** 生成一个明显错误的签名。 */
    public static String wrongSignature() {
        return "0000000000000000000000000000000000000000";
    }

    /** 当前时间戳（秒）。 */
    public static String nowTimestamp() {
        return String.valueOf(System.currentTimeMillis() / 1000L);
    }

    /** 偏移指定秒数的时间戳。 */
    public static String timestampOffset(long offsetSeconds) {
        return String.valueOf(System.currentTimeMillis() / 1000L + offsetSeconds);
    }

    private static String sha1(String input) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-1").digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }
}
