package com.lumensteward.clawbot.domain.game;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 密码破译引擎测试（W19）：密文合法性、nA mB 评定、掩码揭示——纯域逻辑，确定性断言。
 */
class CodeBreakEngineTest {

    @Test
    @DisplayName("生成密文：恰好 4 位、全为数字、互不重复；多次生成覆盖到含 0 的组合")
    void shouldGenerateDistinctFourDigitSecret() {
        Random random = new Random(42);
        boolean sawLeadingZero = false;
        for (int i = 0; i < 200; i++) {
            String secret = CodeBreakEngine.generateSecret(random);
            assertThat(secret).hasSize(4).matches("\\d{4}");
            assertThat(secret.chars().distinct().count()).isEqualTo(4);
            sawLeadingZero |= secret.charAt(0) == '0';
        }
        assertThat(sawLeadingZero).as("200 次生成应覆盖到首位为 0 的密文").isTrue();
    }

    @Test
    @DisplayName("猜测格式校验：4 位互不重复数字才合法（位数/非数字/重复/null 全拒）")
    void shouldValidateGuessFormat() {
        assertThat(CodeBreakEngine.isValidGuess("0123")).isTrue();
        assertThat(CodeBreakEngine.isValidGuess("0273")).isTrue();
        assertThat(CodeBreakEngine.isValidGuess("012")).isFalse();
        assertThat(CodeBreakEngine.isValidGuess("01234")).isFalse();
        assertThat(CodeBreakEngine.isValidGuess("01a3")).isFalse();
        assertThat(CodeBreakEngine.isValidGuess("0113")).isFalse();
        assertThat(CodeBreakEngine.isValidGuess(null)).isFalse();
    }

    @Test
    @DisplayName("nA mB 评定：全中 4A0B；全错 0A0B；数字对位置错 0A4B；混合 2A1B")
    void shouldEvaluateFeedback() {
        assertThat(CodeBreakEngine.evaluate("0123", "0123"))
                .isEqualTo(new CodeBreakEngine.Feedback(4, 0));
        assertThat(CodeBreakEngine.evaluate("0123", "4567"))
                .isEqualTo(new CodeBreakEngine.Feedback(0, 0));
        assertThat(CodeBreakEngine.evaluate("0123", "3210"))
                .isEqualTo(new CodeBreakEngine.Feedback(0, 4));
        assertThat(CodeBreakEngine.evaluate("0123", "0134"))
                .isEqualTo(new CodeBreakEngine.Feedback(2, 1));
        assertThat(CodeBreakEngine.evaluate("0123", "1034"))
                .isEqualTo(new CodeBreakEngine.Feedback(0, 3));
        // 经典边界：被全中消费的数字不得再计错位（单遍实现会给出 1A1B 的错误结果）
        assertThat(CodeBreakEngine.evaluate("0123", "0044"))
                .isEqualTo(new CodeBreakEngine.Feedback(1, 0));
    }

    @Test
    @DisplayName("揭示提示：每次只揭示一位未揭示位；全部揭示后原样返回")
    void shouldRevealOnePositionAtATime() {
        Random random = new Random(7);
        String mask = CodeBreakEngine.revealOne("0123", "····", random);
        assertThat(mask).hasSize(4);
        assertThat(countDots(mask)).isEqualTo(3);
        assertThat(mask.charAt(indexOfReveal(mask))).isIn('0', '1', '2', '3');

        String mask2 = CodeBreakEngine.revealOne("0123", mask, random);
        assertThat(countDots(mask2)).isEqualTo(2);

        String full = "0123";
        assertThat(CodeBreakEngine.revealOne("0123", full, random)).isEqualTo(full);
    }

    private static int countDots(String mask) {
        return (int) mask.chars().filter(c -> c == '·').count();
    }

    private static int indexOfReveal(String mask) {
        for (int i = 0; i < mask.length(); i++) {
            if (mask.charAt(i) != '·') {
                return i;
            }
        }
        return -1;
    }
}
