package com.lumensteward.clawbot.domain.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lumensteward.clawbot.common.util.JsonUtils;
import com.lumensteward.clawbot.domain.game.GameSession;
import com.lumensteward.clawbot.domain.game.LifeCharacter;
import com.lumensteward.clawbot.domain.tool.impl.LifeSimTool;
import com.lumensteward.clawbot.support.InMemoryGameStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模拟人生对局工具测试（迭代 4 W19）：会话门控可见性、回合结算落库、状态查询与结局关局
 * （结局路径以极低健康角色反复 live 至终局，非确定性但概率上必然收敛，上限 500 回合）。
 */
class LifeSimToolTest {

    private static final String OPENID = "openid-life";

    private final InMemoryGameStore store = new InMemoryGameStore();
    private final LifeSimTool tool = new LifeSimTool(store);

    @Test
    @DisplayName("可见性：仅当本用户模拟人生对局进行中（activeGame=life_sim）才可见")
    void shouldBeVisibleOnlyWhenOwnSessionActive() {
        assertThat(tool.visibleIn(new ToolVisibilityContext(OPENID, false, "life_sim"))).isTrue();
        assertThat(tool.visibleIn(new ToolVisibilityContext(OPENID, false, "code_break"))).isFalse();
        assertThat(tool.visibleIn(new ToolVisibilityContext(OPENID, false, null))).isFalse();
        assertThat(new LifeSimTool(null).visibleIn(
                new ToolVisibilityContext(OPENID, false, "life_sim"))).isFalse();
    }

    @Test
    @DisplayName("自述元数据：判定词收「模拟人生/角色」、归因域 game")
    void shouldSelfDescribeMetadata() {
        assertThat(tool.name()).isEqualTo("life_sim");
        assertThat(tool.claimKeywords()).containsExactlyInAnyOrder("模拟人生", "角色");
        assertThat(tool.monitorDomain()).isEqualTo("game");
        assertThat(tool.readOnly()).isFalse();
        assertThat(tool.critical()).isFalse();
    }

    @Test
    @DisplayName("live 缺 choice：INVALID_ARGS，回合不推进")
    void shouldRejectLiveWithoutChoice() {
        seed();
        assertThat(tool.execute(ctx(), liveArgs(null)).errorType()).isEqualTo("INVALID_ARGS");
        assertThat(tool.execute(ctx(), liveArgs("  ")).errorType()).isEqualTo("INVALID_ARGS");
        assertThat(store.find(OPENID).orElseThrow().character().turn()).isZero();
    }

    @Test
    @DisplayName("live：角色长一岁、回合 +1 并落库；summary 为事实性结果")
    void shouldAdvanceCharacterAndPersist() {
        seed();
        LifeCharacter before = store.find(OPENID).orElseThrow().character();

        ToolResult result = tool.execute(ctx(), liveArgs("认真工作"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.data().path("summary").asText()).isNotBlank();
        assertThat(result.data().path("ended").asBoolean()).isFalse();
        LifeCharacter after = store.find(OPENID).orElseThrow().character();
        assertThat(after.age()).isEqualTo(before.age() + 1);
        assertThat(after.turn()).isEqualTo(before.turn() + 1);
    }

    @Test
    @DisplayName("status：只读汇报，不推进回合不写库")
    void statusShouldNotMutateSession() {
        seed();
        GameSession before = store.find(OPENID).orElseThrow();

        ToolResult result = tool.execute(ctx(), args("status"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.data().path("character").path("age").asInt()).isEqualTo(before.character().age());
        assertThat(store.find(OPENID).orElseThrow()).isSameAs(before);
    }

    @Test
    @DisplayName("结局：健康见底后关局（ended=true 且会话删除）")
    void shouldCloseSessionOnEnding() {
        store.save(OPENID, GameSession.forLifeSim(
                new LifeCharacter(24, 2, 60, 60, 60, 10), Instant.now()));

        ToolResult result = null;
        for (int i = 0; i < 500 && store.find(OPENID).isPresent(); i++) {
            result = tool.execute(ctx(), liveArgs("硬撑着过这一年"));
        }

        assertThat(result).as("健康=2 时 500 回合内必然触底终局").isNotNull();
        assertThat(result.data().path("ended").asBoolean()).isTrue();
        assertThat(result.data().path("ending").asText()).isNotBlank();
        assertThat(store.find(OPENID)).isEmpty();
    }

    @Test
    @DisplayName("无对局或异局：SESSION_EXPIRED 如实提示（BR-09）")
    void shouldFailWhenNoOwnSession() {
        assertThat(tool.execute(ctx(), liveArgs("工作")).errorType()).isEqualTo("SESSION_EXPIRED");
        store.save(OPENID, GameSession.forTurtleSoup("ts001", Instant.now()));
        assertThat(tool.execute(ctx(), liveArgs("工作")).errorType()).isEqualTo("SESSION_EXPIRED");
    }

    private void seed() {
        store.save(OPENID, GameSession.forLifeSim(
                new LifeCharacter(18, 70, 65, 55, 60, 0), Instant.now()));
    }

    private static ToolContext ctx() {
        return ToolContext.of("trace-life", OPENID, 1L, 0);
    }

    private static JsonNode liveArgs(String choice) {
        ObjectNode node = JsonUtils.mapper().createObjectNode();
        node.put("action", "live");
        if (choice != null) {
            node.put("choice", choice);
        }
        return node;
    }

    private static JsonNode args(String action) {
        ObjectNode node = JsonUtils.mapper().createObjectNode();
        node.put("action", action);
        return node;
    }
}
