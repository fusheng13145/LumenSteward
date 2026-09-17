package com.lumensteward.clawbot.application.safety;

/**
 * 内容安全结论（架构 5.2 / SRS FR-09，BR-12 Fail-Closed）。
 *
 * @param passed             是否通过
 * @param hitWord            命中词（通过时可空）
 * @param serviceUnavailable 审核服务/词库是否不可用（true 且 Fail-Closed 时判为不通过）
 */
public record SafetyVerdict(boolean passed, String hitWord, boolean serviceUnavailable) {

    /** 通过。 */
    public static SafetyVerdict pass() {
        return new SafetyVerdict(true, null, false);
    }

    /** 命中敏感词。 */
    public static SafetyVerdict hit(String hitWord) {
        return new SafetyVerdict(false, hitWord, false);
    }

    /** 服务不可用（Fail-Closed）。 */
    public static SafetyVerdict unavailable() {
        return new SafetyVerdict(false, null, true);
    }
}
