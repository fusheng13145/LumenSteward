package com.lumensteward.clawbot.domain.game;

/**
 * 桌游类型（迭代 4 W19 单人桌游技能，D2 的 P1「桌游 ≥3 类」承担者）。
 *
 * <p>三类单人玩法各对应一个会话门控工具（{@code toolName}），由恒可见的桌游大厅
 * （{@code game_lobby}）开局/关局；进行中仅当局游戏具可见（W10 可见性机制，
 * 保证每轮下发 Schema ≤8 的 P2 判据不被广度扩张突破）。
 */
public enum GameType {

    /** 海龟汤（情境猜谜）：模型任主持人依汤底答是非，工具管提示与关局。 */
    TURTLE_SOUP("turtle_soup", "海龟汤"),

    /** 密码破译（数字 Bulls & Cows）：密文只存服务端，模型仅转发猜测取反馈。 */
    CODE_BREAK("code_break", "密码破译"),

    /** 模拟人生（文字人生模拟）：引擎按事件库推进角色状态，模型据实叙事。 */
    LIFE_SIM("life_sim", "模拟人生");

    private final String toolName;
    private final String displayName;

    GameType(String toolName, String displayName) {
        this.toolName = toolName;
        this.displayName = displayName;
    }

    /** 承载本玩法的会话门控工具名（与 {@code Tool#name()} 一致）。 */
    public String toolName() {
        return toolName;
    }

    /** 对用户展示的中文名。 */
    public String displayName() {
        return displayName;
    }

    /**
     * 按工具名反查玩法（可见性上下文 {@code activeGame} 存的就是工具名）。
     *
     * @param toolName 工具名
     * @return 对应玩法；未匹配返回 empty
     */
    public static java.util.Optional<GameType> byToolName(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return java.util.Optional.empty();
        }
        for (GameType type : values()) {
            if (type.toolName.equals(toolName)) {
                return java.util.Optional.of(type);
            }
        }
        return java.util.Optional.empty();
    }

    /**
     * 按大厅入参识别玩法：接受工具名（{@code turtle_soup}）或中文名（{@code 海龟汤}）。
     *
     * @param input 大厅 {@code game} 参数
     * @return 对应玩法；未匹配返回 empty
     */
    public static java.util.Optional<GameType> byInput(String input) {
        if (input == null || input.isBlank()) {
            return java.util.Optional.empty();
        }
        String normalized = input.trim();
        for (GameType type : values()) {
            if (type.toolName.equals(normalized) || type.displayName.equals(normalized)) {
                return java.util.Optional.of(type);
            }
        }
        return java.util.Optional.empty();
    }
}
