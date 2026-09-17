package com.lumensteward.clawbot.interfaces.admin;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 客户端真实 IP 解析（审计用，FR-16 / 8.5）。
 *
 * <p>优先取反向代理透传的 {@code X-Forwarded-For} 首段，其次 {@code X-Real-IP}，最后回落
 * {@code getRemoteAddr()}。长度限定 IPv6 兼容（≤45）。
 */
public final class ClientIp {

    private static final String X_FORWARDED_FOR = "X-Forwarded-For";
    private static final String X_REAL_IP = "X-Real-IP";
    private static final int MAX_LENGTH = 45;

    private ClientIp() {
        // 工具类禁止实例化
    }

    /**
     * 解析客户端 IP。
     *
     * @param request HTTP 请求
     * @return IP 字符串（无法解析时返回 {@code unknown}）
     */
    public static String of(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        String forwarded = request.getHeader(X_FORWARDED_FOR);
        if (forwarded != null && !forwarded.isBlank()) {
            return truncate(forwarded.split(",")[0].trim());
        }
        String realIp = request.getHeader(X_REAL_IP);
        if (realIp != null && !realIp.isBlank()) {
            return truncate(realIp.trim());
        }
        return truncate(request.getRemoteAddr());
    }

    private static String truncate(String ip) {
        if (ip == null || ip.isBlank()) {
            return "unknown";
        }
        return ip.length() > MAX_LENGTH ? ip.substring(0, MAX_LENGTH) : ip;
    }
}
