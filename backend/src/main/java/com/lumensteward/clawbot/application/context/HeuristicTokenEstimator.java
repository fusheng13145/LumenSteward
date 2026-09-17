package com.lumensteward.clawbot.application.context;

import com.lumensteward.clawbot.infrastructure.client.llm.dto.ChatMessage;
import org.springframework.stereotype.Component;

/**
 * 启发式 token 估算（架构 5.2）。
 *
 * <p>无需引入 tokenizer 依赖：中日韩字符按 1 token/字、其余字符按 1 token/4 字符估算，向上取整。
 * 该估算偏保守（略高），用于上下文裁剪更安全（宁可多裁一点，也避免超窗）。
 */
@Component
public class HeuristicTokenEstimator implements TokenEstimator {

    /** 每条消息的固定开销（role/分隔符等）。 */
    private static final int PER_MESSAGE_OVERHEAD = 4;

    @Override
    public int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            if (isCjk(text.charAt(i))) {
                cjk++;
            } else {
                other++;
            }
        }
        int tokens = cjk + (int) Math.ceil(other / 4.0);
        return Math.max(1, tokens);
    }

    @Override
    public int estimate(ChatMessage message) {
        if (message == null) {
            return 0;
        }
        return estimate(message.content()) + PER_MESSAGE_OVERHEAD + toolCallTokens(message);
    }

    private int toolCallTokens(ChatMessage message) {
        if (message.toolCalls() == null || message.toolCalls().isEmpty()) {
            return 0;
        }
        int total = 0;
        for (var call : message.toolCalls()) {
            total += estimate(call.functionName()) + estimate(call.argumentsJson());
        }
        return total;
    }

    private static boolean isCjk(char c) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A
                || block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS
                || block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION
                || block == Character.UnicodeBlock.HIRAGANA
                || block == Character.UnicodeBlock.KATAKANA
                || block == Character.UnicodeBlock.HANGUL_SYLLABLES;
    }
}
