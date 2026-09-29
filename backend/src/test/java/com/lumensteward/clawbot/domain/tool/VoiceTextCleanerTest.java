package com.lumensteward.clawbot.domain.tool;

import com.lumensteward.clawbot.domain.tool.impl.VoiceTextCleaner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 语音文案清洗测试（迭代 4 W12：合成前剥除括号旁注，母本 M-2 §03-4 教训）。
 */
class VoiceTextCleanerTest {

    @Test
    @DisplayName("中文与半角括号旁注均被剥除，多余空白收紧")
    void stripsParentheticalAsides() {
        assertThat(VoiceTextCleaner.clean("杭州今天晴，26℃（演示数据集样例，非真实预报）。"))
                .isEqualTo("杭州今天晴，26℃ 。");
        assertThat(VoiceTextCleaner.clean("识别完成 (已为你匹配到档案：豆豆)，请看结果。"))
                .isEqualTo("识别完成 ，请看结果。");
        assertThat(VoiceTextCleaner.clean("好的（笑）马上来")).isEqualTo("好的 马上来");
    }

    @Test
    @DisplayName("清洗后为空（整条都是旁注）→ 回落原文，不返回空白")
    void fallsBackToOriginalWhenEmpty() {
        assertThat(VoiceTextCleaner.clean("（笑）")).isEqualTo("（笑）");
        assertThat(VoiceTextCleaner.clean(" (ok) ")).isEqualTo("(ok)");
    }

    @Test
    @DisplayName("无旁注的普通文本原样保留；空值原样返回")
    void keepsPlainTextInput() {
        assertThat(VoiceTextCleaner.clean("今天天气不错，适合出门。"))
                .isEqualTo("今天天气不错，适合出门。");
        assertThat(VoiceTextCleaner.clean("")).isEmpty();
        assertThat(VoiceTextCleaner.clean(null)).isNull();
    }
}
