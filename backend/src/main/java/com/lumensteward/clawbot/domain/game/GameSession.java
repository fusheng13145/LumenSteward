package com.lumensteward.clawbot.domain.game;

import java.time.Instant;
import java.util.Objects;

/**
 * 一局进行中的桌游会话（W19；单用户同时至多一局，见 {@link GameStore} 契约）。
 *
 * <p>record 按 {@link #game} 只填相关分量，其余为 null/0——不引入继承层次，保持
 * JSON 往返（Redis 值）与构造的最小成本。密文（{@code secretCode}）只存本会话，
 * 由密码破译工具在执行侧使用，<b>绝不进入任何面向模型的回复字段</b>。
 *
 * @param game       玩法类型
 * @param puzzleId   海龟汤：当前谜题编号（其余玩法为 null）
 * @param hintsUsed  海龟汤：已给出的递进提示条数（其余玩法为 0）
 * @param secretCode 密码破译：服务端持有的 4 位密文（其余玩法为 null）
 * @param codeMask   密码破译：已揭示位掩码串，未揭示位为 '·'，如 {@code "·7··"}
 * @param attempts   密码破译：已消耗机会数（含猜测与提示罚次；其余玩法为 0）
 * @param character  模拟人生：角色状态（其余玩法为 null）
 * @param startedAt  开局时间
 */
public record GameSession(GameType game, String puzzleId, int hintsUsed, String secretCode,
                          String codeMask, int attempts, LifeCharacter character,
                          Instant startedAt) {

    /** 海龟汤开局。 */
    public static GameSession forTurtleSoup(String puzzleId, Instant startedAt) {
        return new GameSession(GameType.TURTLE_SOUP, Objects.requireNonNull(puzzleId, "puzzleId"),
                0, null, null, 0, null, startedAt);
    }

    /** 密码破译开局（掩码全部未揭示）。 */
    public static GameSession forCodeBreak(String secretCode, Instant startedAt) {
        String mask = "·".repeat(CodeBreakEngine.CODE_LENGTH);
        return new GameSession(GameType.CODE_BREAK, null, 0,
                Objects.requireNonNull(secretCode, "secretCode"), mask, 0, null, startedAt);
    }

    /** 模拟人生开局。 */
    public static GameSession forLifeSim(LifeCharacter character, Instant startedAt) {
        return new GameSession(GameType.LIFE_SIM, null, 0, null, null, 0,
                Objects.requireNonNull(character, "character"), startedAt);
    }

    /** 返回提示数 +1 的海龟汤会话。 */
    public GameSession withHintsUsed(int newHintsUsed) {
        return new GameSession(game, puzzleId, newHintsUsed, secretCode, codeMask, attempts,
                character, startedAt);
    }

    /** 返回机会数与掩码更新后的密码破译会话。 */
    public GameSession withCodeProgress(int newAttempts, String newCodeMask) {
        return new GameSession(game, puzzleId, hintsUsed, secretCode, newCodeMask, newAttempts,
                character, startedAt);
    }

    /** 返回角色更新后的模拟人生会话。 */
    public GameSession withCharacter(LifeCharacter newCharacter) {
        return new GameSession(game, puzzleId, hintsUsed, secretCode, codeMask, attempts,
                newCharacter, startedAt);
    }
}
