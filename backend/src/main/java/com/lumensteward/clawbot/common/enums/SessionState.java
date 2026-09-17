package com.lumensteward.clawbot.common.enums;

import lombok.Getter;

import java.util.Optional;

/**
 * 会话状态（对齐 {@code wx_session.state} 字段 COMMENT，7.6.3 / 5.2）。
 *
 * <p>与前端 {@code src/utils/constants.ts} 中 SESSION_STATE 同源。
 */
@Getter
public enum SessionState {

    /** 空闲。 */
    IDLE("IDLE", "空闲"),
    /** 闲聊中。 */
    CHATTING("CHATTING", "闲聊中"),
    /** 任务执行中。 */
    TASKING("TASKING", "任务执行中"),
    /** 降级（依赖不可用，走无状态/兜底，9.5）。 */
    DEGRADED("DEGRADED", "降级");

    /** 落库字符串值。 */
    private final String code;

    /** 中文含义。 */
    private final String label;

    SessionState(String code, String label) {
        this.code = code;
        this.label = label;
    }

    /**
     * 按落库值查找状态。
     *
     * @param code 落库值
     * @return 匹配的状态
     */
    public static Optional<SessionState> findByCode(String code) {
        if (code == null) {
            return Optional.empty();
        }
        for (SessionState state : values()) {
            if (state.code.equalsIgnoreCase(code)) {
                return Optional.of(state);
            }
        }
        return Optional.empty();
    }
}
