package com.lumensteward.clawbot.domain.game;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模拟人生引擎测试（W19）：开局角色、回合结算不变量与两条结局路径——结局用
 * 包内可见的 {@code settle} 注入确定事件验证，不依赖随机。
 */
class LifeSimEngineTest {

    @Test
    @DisplayName("开局角色：18 岁、四维属性在设定区间、回合数 0")
    void shouldCreateStartingCharacter() {
        Random random = new Random(1);
        for (int i = 0; i < 50; i++) {
            LifeCharacter c = LifeSimEngine.create(random);
            assertThat(c.age()).isEqualTo(18);
            assertThat(c.turn()).isZero();
            assertThat(c.health()).isBetween(65, 75);
            assertThat(c.mind()).isBetween(60, 70);
            assertThat(c.wealth()).isBetween(50, 65);
            assertThat(c.social()).isBetween(55, 65);
        }
    }

    @Test
    @DisplayName("live 不变量：每回合长一岁、回合数 +1、属性恒在 0~100、事件摘要非空")
    void liveShouldAgeOneYearAndClampAttributes() {
        Random random = new Random(11);
        LifeCharacter c = LifeSimEngine.create(random);
        for (int i = 0; i < 60; i++) {
            LifeSimEngine.LifeOutcome outcome = LifeSimEngine.live(c, "认真生活", random);
            assertThat(outcome.summary()).isNotBlank();
            LifeCharacter after = outcome.after();
            assertThat(after.age()).isEqualTo(c.age() + 1);
            assertThat(after.turn()).isEqualTo(c.turn() + 1);
            assertThat(after.health()).isBetween(0, 100);
            assertThat(after.mind()).isBetween(0, 100);
            assertThat(after.wealth()).isBetween(0, 100);
            assertThat(after.social()).isBetween(0, 100);
            assertThat(outcome.ended()).isEqualTo(outcome.ending() != null);
            if (outcome.ended()) {
                break;
            }
            c = after;
        }
    }

    @Test
    @DisplayName("结局判定：健康归零 → 提前谢幕；年满三十 → 三十而立收官")
    void settleShouldDetectBothEndings() {
        LifeSimEngine.LifeOutcome dead = LifeSimEngine.settle(
                new LifeCharacter(24, 5, 60, 60, 60, 10),
                new LifeSimEngine.LifeEvent("一场大病", -10, 0, 0, 0));
        assertThat(dead.ended()).isTrue();
        assertThat(dead.after().health()).isZero();
        assertThat(dead.ending()).contains("句号").contains("25");

        LifeSimEngine.LifeOutcome retired = LifeSimEngine.settle(
                new LifeCharacter(29, 60, 60, 60, 60, 30),
                new LifeSimEngine.LifeEvent("平稳的一年", 0, 0, 0, 0));
        assertThat(retired.ended()).isTrue();
        assertThat(retired.after().age()).isEqualTo(30);
        assertThat(retired.ending()).contains("三十而立");

        LifeSimEngine.LifeOutcome normal = LifeSimEngine.settle(
                new LifeCharacter(22, 60, 60, 60, 60, 10),
                new LifeSimEngine.LifeEvent("平稳的一年", 0, 0, 0, 0));
        assertThat(normal.ended()).isFalse();
        assertThat(normal.ending()).isNull();
    }

    @Test
    @DisplayName("属性收敛：越界增量被夹到 0~100（不会出现负数或爆表）")
    void clampShouldBoundAttributes() {
        LifeSimEngine.LifeOutcome outcome = LifeSimEngine.settle(
                new LifeCharacter(20, 3, 99, 1, 99, 5),
                new LifeSimEngine.LifeEvent("极端的一年", -20, 20, -20, 20));
        LifeCharacter after = outcome.after();
        assertThat(after.health()).isZero();
        assertThat(after.mind()).isEqualTo(100);
        assertThat(after.wealth()).isZero();
        assertThat(after.social()).isEqualTo(100);
    }
}
