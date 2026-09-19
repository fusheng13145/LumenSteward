package com.lumensteward.clawbot.application.validation;

import com.lumensteward.clawbot.application.config.ConfigKeys;
import com.lumensteward.clawbot.application.config.DynamicConfigService;
import org.springframework.stereotype.Component;

/**
 * 单条消息长度守卫（FR-20 ④）。
 *
 * <p>文本超过上限（默认 2000 字符，可经 {@code rate_limit.max_message_length} 运行时调整）时截断，
 * 并附系统提示说明，避免超长输入冲击上下文与下游服务。
 */
@Component
public final class MessageLengthGuard {

    /** 默认上限。 */
    public static final int DEFAULT_MAX_LENGTH = 2000;

    /** 守卫结果。 */
    public record GuardResult(String text, boolean truncated, int originalLength) {
    }

    private final DynamicConfigService dynamicConfig;

    /**
     * 构造器注入（G-14）。
     *
     * @param dynamicConfig 动态配置（可为 null）
     */
    public MessageLengthGuard(DynamicConfigService dynamicConfig) {
        this.dynamicConfig = dynamicConfig;
    }

    /**
     * 守卫消息文本。
     *
     * @param text 原始文本（可空）
     * @return 守卫结果
     */
    public GuardResult guard(String text) {
        int max = dynamicConfig == null ? DEFAULT_MAX_LENGTH
                : dynamicConfig.getInt(ConfigKeys.RATE_LIMIT_MAX_MESSAGE_LENGTH, DEFAULT_MAX_LENGTH);
        if (text == null || text.length() <= max) {
            return new GuardResult(text, false, text == null ? 0 : text.length());
        }
        String truncated = text.substring(0, max);
        String noted = "（用户原文较长，已截断至前 " + max + " 字符处理）\n" + truncated;
        return new GuardResult(noted, true, text.length());
    }
}
