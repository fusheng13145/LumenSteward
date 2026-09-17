package com.lumensteward.clawbot.domain.tool;

import java.util.List;

/**
 * 参数校验结果（架构 5.1）。
 *
 * @param valid      是否通过
 * @param violations 违规明细（面向模型的可读说明，命中多条时按出现顺序排列）
 */
public record ValidationResult(boolean valid, List<String> violations) {

    public ValidationResult {
        violations = violations == null ? List.of() : List.copyOf(violations);
    }

    /** 通过。 */
    public static ValidationResult ok() {
        return new ValidationResult(true, List.of());
    }

    /** 失败（多条违规）。 */
    public static ValidationResult fail(List<String> violations) {
        return new ValidationResult(false, violations);
    }

    /** 失败（单条违规）。 */
    public static ValidationResult fail(String violation) {
        return new ValidationResult(false, List.of(violation));
    }

    /** 合并违规明细为单行文本，便于回注模型（SRS 9.4.3 第 33 行 describeViolation）。 */
    public String describe() {
        return String.join("；", violations);
    }
}
