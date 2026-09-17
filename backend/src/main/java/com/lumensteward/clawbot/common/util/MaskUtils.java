package com.lumensteward.clawbot.common.util;

/**
 * 出参脱敏工具（8.5 / BR-21 / BR-15 / BR-30）。
 *
 * <p>规则与前端 {@code src/utils/mask.ts} <b>同源</b>，用于用户/会话/日志的统一脱敏；
 * 脱敏失败时返回 {@code null} 并由调用方丢弃该字段，而非回退到明文（FR-21 异常流 2a）。
 */
public final class MaskUtils {

    private static final String MASK = "****";

    private MaskUtils() {
        // 工具类禁止实例化
    }

    /**
     * openid 脱敏：前 4 位 + {@code ****} + 后 4 位（BR-21）。
     *
     * @param openid 原始 openid
     * @return 脱敏后的 openid；入参为空白时返回 null
     */
    public static String openid(String openid) {
        return keepHeadAndTail(openid, 4, 4);
    }

    /**
     * 运单号脱敏：形如 {@code SF12****7890}（BR-15）。
     *
     * @param trackingNo 原始运单号
     * @return 脱敏后的运单号；入参为空白时返回 null
     */
    public static String trackingNo(String trackingNo) {
        return keepHeadAndTail(trackingNo, 4, 4);
    }

    /**
     * 密钥类脱敏：仅保留尾 4 位，形如 {@code ****ab12}。
     *
     * @param value 原始密钥
     * @return 脱敏后的密钥；入参为空白时返回 null
     */
    public static String secret(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        if (value.length() <= 4) {
            return MASK;
        }
        return MASK + value.substring(value.length() - 4);
    }

    /**
     * 手机号脱敏：前 3 位 + {@code ****} + 后 4 位。
     *
     * @param phone 原始手机号
     * @return 脱敏后的手机号；入参为空白时返回 null
     */
    public static String phone(String phone) {
        return keepHeadAndTail(phone, 3, 4);
    }

    private static String keepHeadAndTail(String value, int head, int tail) {
        if (value == null || value.isBlank()) {
            return null;
        }
        // 长度不足以同时保留头尾时，整体打码，避免泄露
        if (value.length() <= head + tail) {
            return MASK;
        }
        return value.substring(0, head) + MASK + value.substring(value.length() - tail);
    }
}
