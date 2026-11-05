package com.lumensteward.clawbot.domain.game;

import java.util.Random;

/**
 * 密码破译引擎（W19，数字版 Bulls & Cows，纯函数域逻辑）。
 *
 * <p>公平性由本引擎与存储共同保证：密文只存在 {@code GameSession}（Redis，对模型不可见），
 * 模型仅转发用户的猜测并转述 nA mB 反馈——它永远拿不到答案，也就无法（也不必）假装保密。
 */
public final class CodeBreakEngine {

    /** 密码位数。 */
    public static final int CODE_LENGTH = 4;

    /** 机会总数（含提示罚次）。 */
    public static final int MAX_ATTEMPTS = 8;

    private CodeBreakEngine() {
        // 纯函数域逻辑，禁止实例化
    }

    /**
     * 生成 4 位互不重复的数字密文（各位可含 0，首位不限）。
     *
     * @param random 随机源
     * @return 形如 {@code "0273"} 的密文
     */
    public static String generateSecret(Random random) {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        while (sb.length() < CODE_LENGTH) {
            String digit = String.valueOf(random.nextInt(10));
            if (sb.indexOf(digit) < 0) {
                sb.append(digit);
            }
        }
        return sb.toString();
    }

    /**
     * 猜测格式校验：恰好 4 位、全为数字、互不重复。
     *
     * @param guess 用户猜测
     * @return 合法返回 true
     */
    public static boolean isValidGuess(String guess) {
        if (guess == null || guess.length() != CODE_LENGTH) {
            return false;
        }
        for (int i = 0; i < guess.length(); i++) {
            char c = guess.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
            if (guess.indexOf(c, i + 1) >= 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * 评定一次猜测：{@code exact} 为位置与数字都对的数量（A），{@code misplaced} 为
     * 数字对但位置错的数量（B）。标准两遍算法：先计全中，再按数字余量计错位——
     * 已被全中消费的数字不再计入错位（经典单遍实现会双重计数）。
     *
     * @param secret 密文
     * @param guess  已通过 {@link #isValidGuess(String)} 的猜测
     * @return 反馈
     */
    public static Feedback evaluate(String secret, String guess) {
        int exact = 0;
        int[] residue = new int[10];
        for (int i = 0; i < CODE_LENGTH; i++) {
            char s = secret.charAt(i);
            if (s == guess.charAt(i)) {
                exact++;
            } else {
                residue[s - '0']++;
            }
        }
        int misplaced = 0;
        for (int i = 0; i < CODE_LENGTH; i++) {
            char g = guess.charAt(i);
            if (secret.charAt(i) != g && residue[g - '0'] > 0) {
                residue[g - '0']--;
                misplaced++;
            }
        }
        return new Feedback(exact, misplaced);
    }

    /**
     * 在掩码中随机揭示一位仍未揭示的数字。
     *
     * @param secret 密文
     * @param mask   当前掩码（未揭示位为 {@code '·'}）
     * @param random 随机源
     * @return 新掩码；全部已揭示时原样返回
     */
    public static String revealOne(String secret, String mask, Random random) {
        StringBuilder sb = new StringBuilder(mask);
        int unrevealed = 0;
        for (int i = 0; i < sb.length(); i++) {
            if (sb.charAt(i) == '·') {
                unrevealed++;
            }
        }
        if (unrevealed == 0) {
            return mask;
        }
        int target = random.nextInt(unrevealed);
        for (int i = 0; i < sb.length(); i++) {
            if (sb.charAt(i) == '·' && target-- == 0) {
                sb.setCharAt(i, secret.charAt(i));
                break;
            }
        }
        return sb.toString();
    }

    /** 一次猜测的反馈（nA mB）。 */
    public record Feedback(int exact, int misplaced) {
    }
}
