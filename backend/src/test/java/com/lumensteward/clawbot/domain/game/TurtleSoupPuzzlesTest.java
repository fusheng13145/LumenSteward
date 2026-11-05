package com.lumensteward.clawbot.domain.game;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 海龟汤谜题库测试（W19）：内容完整性、编号唯一性、抽取与回查。
 */
class TurtleSoupPuzzlesTest {

    @Test
    @DisplayName("每题三件套完整：汤面/汤底非空、递进提示 ≥2 条、判定要点 ≥2 条")
    void everyPuzzleShouldBeComplete() {
        assertThat(TurtleSoupPuzzles.SIZE).isEqualTo(8);
        for (int i = 1; i <= TurtleSoupPuzzles.SIZE; i++) {
            String id = String.format("ts%03d", i);
            Optional<TurtleSoupPuzzles.TurtleSoupPuzzle> puzzle = TurtleSoupPuzzles.byId(id);
            assertThat(puzzle).as("谜题 %s 应存在", id).isPresent();
            assertThat(puzzle.get().surface()).isNotBlank();
            assertThat(puzzle.get().solution()).isNotBlank();
            assertThat(puzzle.get().hints()).hasSizeGreaterThanOrEqualTo(2);
            assertThat(puzzle.get().keyPoints()).hasSizeGreaterThanOrEqualTo(2);
        }
    }

    @Test
    @DisplayName("编号唯一且连续（ts001 ~ ts008）")
    void idsShouldBeUniqueAndSequential() {
        Set<String> ids = new HashSet<>();
        for (int i = 1; i <= TurtleSoupPuzzles.SIZE; i++) {
            Optional<TurtleSoupPuzzles.TurtleSoupPuzzle> puzzle =
                    TurtleSoupPuzzles.byId(String.format("ts%03d", i));
            assertThat(puzzle).as("编号 %03d 应存在", i).isPresent();
            ids.add(puzzle.get().id());
        }
        assertThat(ids).hasSize(TurtleSoupPuzzles.SIZE);
    }

    @Test
    @DisplayName("draw 随机抽取：恒命中库内题目；byId 对未知/空编号返回空")
    void drawShouldStayInBankAndUnknownIdReturnsEmpty() {
        Random random = new Random(3);
        for (int i = 0; i < 30; i++) {
            TurtleSoupPuzzles.TurtleSoupPuzzle drawn = TurtleSoupPuzzles.draw(random);
            assertThat(TurtleSoupPuzzles.byId(drawn.id())).isPresent();
        }
        assertThat(TurtleSoupPuzzles.byId("ts999")).isEmpty();
        assertThat(TurtleSoupPuzzles.byId(null)).isEmpty();
        assertThat(TurtleSoupPuzzles.byId("")).isEmpty();
    }
}
