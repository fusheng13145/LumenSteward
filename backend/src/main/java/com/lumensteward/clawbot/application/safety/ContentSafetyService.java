package com.lumensteward.clawbot.application.safety;

/**
 * 内容安全服务（架构 5.2 / SRS FR-09，BR-12 Fail-Closed）。
 */
public interface ContentSafetyService {

    /**
     * 审查文本。
     *
     * @param text 待审查文本
     * @return 审查结论（服务不可用时按 Fail-Closed 返回不通过）
     */
    SafetyVerdict review(String text);
}
