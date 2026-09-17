package com.lumensteward.clawbot.common.enums;

import lombok.Getter;

import java.util.Optional;

/**
 * 工具调用状态（对齐 {@code log_tool_call.status} 字段 COMMENT，7.6.3 / 5.1）。
 *
 * <p>与前端 {@code src/utils/constants.ts} 中 TOOL_STATUS 同源。
 */
@Getter
public enum ToolStatus {

    /** 成功。 */
    SUCCESS(0, "成功"),
    /** 失败。 */
    FAILED(1, "失败"),
    /** 降级（返回兜底结果）。 */
    DEGRADED(2, "降级"),
    /** 超时。 */
    TIMEOUT(3, "超时"),
    /** 未执行（工具未注册 / 参数非法，AC-B8/AC-B9，幻觉判定依据）。 */
    NOT_EXECUTED(4, "未执行");

    /** 落库状态值。 */
    private final int code;

    /** 中文含义。 */
    private final String label;

    ToolStatus(int code, String label) {
        this.code = code;
        this.label = label;
    }

    /**
     * 按落库值查找状态。
     *
     * @param code 落库值
     * @return 匹配的状态
     */
    public static Optional<ToolStatus> findByCode(int code) {
        for (ToolStatus status : values()) {
            if (status.code == code) {
                return Optional.of(status);
            }
        }
        return Optional.empty();
    }
}
