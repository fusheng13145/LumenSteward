package com.lumensteward.clawbot.domain.game;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 模拟人生引擎（W19，纯域逻辑）：每回合按事件库结算一段人生，返回<b>事实性结果</b>
 * 供模型叙事——引擎只裁决数值与结局，不替用户做决定，也不代笔抒情。
 *
 * <p>节奏设计：1 回合 = 1 年，18 岁开局，年满 30 岁以「三十而立」收官（约 12 回合一局）；
 * 健康归零则提前谢幕。事件分通用与情境两池（情境事件按角色当前状态才进入候选）。
 */
public final class LifeSimEngine {

    /** 开局年龄。 */
    public static final int START_AGE = 18;

    /** 收官年龄（三十而立）。 */
    public static final int END_AGE = 30;

    /** 单回合推进的年数。 */
    public static final int YEARS_PER_TURN = 1;

    /** 一次人生回合的结算结果。 */
    public record LifeOutcome(String summary, LifeCharacter after, boolean ended, String ending) {
    }

    /** 单条人生事件：事实性摘要 + 四维增量（包内可见，供确定性单测注入）。 */
    record LifeEvent(String text, int dHealth, int dMind, int dWealth, int dSocial) {
    }

    private record ConditionalEvent(LifeEvent event, java.util.function.Predicate<LifeCharacter> when) {
    }

    private static final List<LifeEvent> GENERAL_EVENTS = List.of(
            new LifeEvent("你坚持晨跑了一个月，身体越来越轻快。", 8, 2, 0, 0),
            new LifeEvent("你报了门线上课程，深夜啃完了最后一章。", -2, 8, 0, 0),
            new LifeEvent("你接了个私活，钱包鼓了一点，觉少了一点。", -4, -2, 8, 0),
            new LifeEvent("你组织了一场老友聚会，通讯录重新热闹起来。", 0, 0, -3, 9),
            new LifeEvent("你熬夜追完了整部剧，第二天顶着黑眼圈上班。", -6, 3, 0, 0),
            new LifeEvent("你把工资的三分之一转进了储蓄账户。", 0, 2, 6, 0),
            new LifeEvent("你学着自己做饭，外卖钱省了不少，胃也舒服了。", 3, 2, 4, 0),
            new LifeEvent("你在地铁上让了个座，收到一个陌生的微笑。", 0, 2, 0, 4),
            new LifeEvent("你陪家人过了完整的一个周末。", 3, 0, -2, 6),
            new LifeEvent("你上班摸鱼被抓了个正着，这个月的绩效黄了。", -2, -4, -6, 0),
            new LifeEvent("你重拾搁置多年的吉他，指尖磨出了茧。", 0, 5, 0, 4),
            new LifeEvent("你顺手帮邻居修好了路由器，收获了两大袋自家种的橘子。", 0, 2, 2, 5));

    private static final List<ConditionalEvent> CONDITIONAL_EVENTS = List.of(
            new ConditionalEvent(new LifeEvent("体检报告亮了红灯，你开始规律作息、戒掉宵夜。", 10, 0, -3, 0),
                    c -> c.health() < 40),
            new ConditionalEvent(new LifeEvent("手头紧得厉害，你同时接了三份兼职，忙到脚不沾地。", -7, -3, 12, 0),
                    c -> c.wealth() < 25),
            new ConditionalEvent(new LifeEvent("老同学突然约你吃饭，你们聊到饭店打烊。", 0, 0, -2, 8),
                    c -> c.social() < 30),
            new ConditionalEvent(new LifeEvent("你开始写日记，把乱成一团的思路一点点捋清了。", 0, 8, 0, 0),
                    c -> c.mind() < 30));

    private LifeSimEngine() {
        // 纯函数域逻辑，禁止实例化
    }

    /**
     * 创建开局角色：18 岁，四维属性在合理区间随机。
     *
     * @param random 随机源
     * @return 初始角色
     */
    public static LifeCharacter create(Random random) {
        return new LifeCharacter(START_AGE,
                LifeCharacter.clamp(65 + random.nextInt(11)),
                LifeCharacter.clamp(60 + random.nextInt(11)),
                LifeCharacter.clamp(50 + random.nextInt(16)),
                LifeCharacter.clamp(55 + random.nextInt(11)),
                0);
    }

    /**
     * 推进一个回合：按角色状态择池抽事件、结算属性增量、长一岁，并判定结局。
     *
     * @param current 当前角色
     * @param choice  玩家这一回合的选择（仅作叙事上下文，不改变事件池，保证引擎可解释）
     * @param random  随机源
     * @return 结算结果；{@code ended=true} 时 {@code ending} 非空
     */
    public static LifeOutcome live(LifeCharacter current, String choice, Random random) {
        List<LifeEvent> candidates = new ArrayList<>(GENERAL_EVENTS);
        for (ConditionalEvent conditional : CONDITIONAL_EVENTS) {
            if (conditional.when().test(current)) {
                candidates.add(conditional.event());
            }
        }
        return settle(current, candidates.get(random.nextInt(candidates.size())));
    }

    /** 结算单个事件（包内可见，供确定性单测注入事件验证结局判定）。 */
    static LifeOutcome settle(LifeCharacter current, LifeEvent event) {
        LifeCharacter after = current.advance(event.dHealth(), event.dMind(),
                event.dWealth(), event.dSocial());
        after = new LifeCharacter(after.age() + YEARS_PER_TURN, after.health(), after.mind(),
                after.wealth(), after.social(), after.turn());
        String summary = event.text();

        if (after.health() <= 0) {
            return new LifeOutcome(summary, after, true,
                    "健康在这一年见了底，人生在这里画上句号。终年 " + after.age() + " 岁。");
        }
        if (after.age() >= END_AGE) {
            return new LifeOutcome(summary, after, true,
                    "年满三十，三十而立——人生第一阶段在这里圆满收官。");
        }
        return new LifeOutcome(summary, after, false, null);
    }
}
