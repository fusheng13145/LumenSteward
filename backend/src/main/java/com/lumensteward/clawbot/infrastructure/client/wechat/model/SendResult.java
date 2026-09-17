package com.lumensteward.clawbot.infrastructure.client.wechat.model;

/**
 * 发送结果（架构 5.1）。
 *
 * @param success   是否成功
 * @param errCode   平台错误码（success=true 时为 0）
 * @param errMsg    平台错误信息
 * @param latencyMs 耗时（ms）
 */
public record SendResult(boolean success, int errCode, String errMsg, long latencyMs) {

    /** 成功。 */
    public static SendResult ok(long latencyMs) {
        return new SendResult(true, 0, null, latencyMs);
    }

    /** 失败。 */
    public static SendResult fail(int errCode, String errMsg, long latencyMs) {
        return new SendResult(false, errCode, errMsg, latencyMs);
    }
}
