package com.lumensteward.clawbot.application.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 预算阈值判定纯函数单测（FR-20 ③）。
 */
class BudgetEvaluatorTest {

    @Test
    @DisplayName("预算<=0 → NORMAL（视为无上限）")
    void budgetZeroNormal() {
        assertThat(BudgetEvaluator.evaluate(1000, 0))
                .isEqualTo(BudgetEvaluator.BudgetStatus.NORMAL);
    }

    @Test
    @DisplayName("50% → NORMAL")
    void fiftyNormal() {
        assertThat(BudgetEvaluator.evaluate(50, 100))
                .isEqualTo(BudgetEvaluator.BudgetStatus.NORMAL);
    }

    @Test
    @DisplayName("恰好 80% → WARN（边界）")
    void eightyWarn() {
        assertThat(BudgetEvaluator.evaluate(80, 100))
                .isEqualTo(BudgetEvaluator.BudgetStatus.WARN);
    }

    @Test
    @DisplayName("恰好 100% → DEGRADED")
    void hundredDegraded() {
        assertThat(BudgetEvaluator.evaluate(100, 100))
                .isEqualTo(BudgetEvaluator.BudgetStatus.DEGRADED);
    }

    @Test
    @DisplayName("超 100% → DEGRADED")
    void overDegraded() {
        assertThat(BudgetEvaluator.evaluate(150, 100))
                .isEqualTo(BudgetEvaluator.BudgetStatus.DEGRADED);
    }

    @Test
    @DisplayName("percent 计算与封顶 100，预算<=0 返回 0")
    void percentCalc() {
        assertThat(BudgetEvaluator.percent(50, 100)).isEqualTo(50);
        assertThat(BudgetEvaluator.percent(80, 100)).isEqualTo(80);
        assertThat(BudgetEvaluator.percent(150, 100)).isEqualTo(100);
        assertThat(BudgetEvaluator.percent(5, 0)).isZero();
    }
}
