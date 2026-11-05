package com.lumensteward.clawbot.domain.game;

/**
 * 模拟人生角色状态（W19，纯数据 record，随 {@link GameSession} 进 Redis JSON）。
 *
 * <p>四维属性取值 0~100（越界由 {@link LifeSimEngine} 收敛）；{@code turn} 为玩家行动
 * 回合计数，每 {@link LifeSimEngine#AGE_STEP_TURNS} 回合角色长一岁。
 *
 * @param age     年龄（开局 18 岁）
 * @param health  健康
 * @param mind    心智
 * @param wealth  财富
 * @param social  人际
 * @param turn    已进行的玩家回合数
 */
public record LifeCharacter(int age, int health, int mind, int wealth, int social, int turn) {

    /** 单维属性上界。 */
    public static final int ATTR_MAX = 100;

    /** 单维属性下界（健康归零即结局）。 */
    public static final int ATTR_MIN = 0;

    /** 收敛单维属性到 0~100。 */
    public static int clamp(int value) {
        return Math.max(ATTR_MIN, Math.min(ATTR_MAX, value));
    }

    /** 返回各维属性施加增量后的新角色（回合数 +1，增量后越界收敛）。 */
    public LifeCharacter advance(int dHealth, int dMind, int dWealth, int dSocial) {
        return new LifeCharacter(age, clamp(health + dHealth), clamp(mind + dMind),
                clamp(wealth + dWealth), clamp(social + dSocial), turn + 1);
    }

    /** 返回长一岁的角色（回合数不变——长岁由回合阈值触发，不再单独计回合）。 */
    public LifeCharacter growOlder() {
        return new LifeCharacter(age + 1, health, mind, wealth, social, turn);
    }
}
