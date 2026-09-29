package com.lumensteward.clawbot.domain.tool.impl;

/**
 * 语音合成前文案清洗（迭代 4 W12 / 母本 M-2 §03-4 实证教训）。
 *
 * <p>模型为组织回复产生的括号旁注（如"（演示数据集样例，非真实预报）""（已为你匹配到档案：豆豆）"）
 * 与语气词标注若原样送入 TTS，会被一字不落地读出来，听感荒谬。本清洗器在合成前剥除
 * 成对括号段（中文（）与半角()），并把清洗产生的多余空白收紧。
 *
 * <p><b>保守策略：</b>清洗后为空（整条都是旁注，如"（笑）"）则回落原文——宁可照读，
 * 也不把"说不出内容"伪装成成功。
 */
public final class VoiceTextCleaner {

    private VoiceTextCleaner() {
        // 静态工具，禁止实例化
    }

    /**
     * 剥除括号旁注并收紧空白。
     *
     * @param text 待合成文本
     * @return 清洗后文本；入参为空原样返回；清洗后为空回落去空白原文
     */
    public static String clean(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String cleaned = text
                .replaceAll("（[^）]*）", " ")
                .replaceAll("\\([^)]*\\)", " ")
                .replaceAll("\\s{2,}", " ")
                .trim();
        return cleaned.isEmpty() ? text.trim() : cleaned;
    }
}
