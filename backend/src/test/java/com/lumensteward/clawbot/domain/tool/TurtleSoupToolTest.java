package com.lumensteward.clawbot.domain.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lumensteward.clawbot.common.util.JsonUtils;
import com.lumensteward.clawbot.domain.game.GameSession;
import com.lumensteward.clawbot.domain.tool.impl.GameLobbyTool;
import com.lumensteward.clawbot.domain.tool.impl.TurtleSoupTool;
import com.lumensteward.clawbot.support.InMemoryGameStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 海龟汤对局工具测试（迭代 4 W19）：会话门控可见性、提示递进、判胜/放弃关局与对局缺失路径。
 */
class TurtleSoupToolTest {

    private static final String OPENID = "openid-soup";

    private final InMemoryGameStore store = new InMemoryGameStore();
    private final TurtleSoupTool tool = new TurtleSoupTool(store);
    private final GameLobbyTool lobby = new GameLobbyTool(store);

    @Test
    @DisplayName("可见性：仅当本用户海龟汤对局进行中（activeGame=turtle_soup）才可见")
    void shouldBeVisibleOnlyWhenOwnSessionActive() {
        assertThat(tool.visibleIn(new ToolVisibilityContext(OPENID, false, "turtle_soup"))).isTrue();
        assertThat(tool.visibleIn(new ToolVisibilityContext(OPENID, false, "code_break"))).isFalse();
        assertThat(tool.visibleIn(new ToolVisibilityContext(OPENID, false, null))).isFalse();
        assertThat(new TurtleSoupTool(store).visibleIn(ToolVisibilityContext.of(OPENID))).isFalse();
        assertThat(new TurtleSoupTool(null).visibleIn(
                new ToolVisibilityContext(OPENID, false, "turtle_soup"))).isFalse();
    }

    @Test
    @DisplayName("自述元数据：判定词收专名（海龟汤/汤面/汤底）、归因域 game")
    void shouldSelfDescribeMetadata() {
        assertThat(tool.name()).isEqualTo("turtle_soup");
        assertThat(tool.claimKeywords()).containsExactlyInAnyOrder("海龟汤", "汤面", "汤底");
        assertThat(tool.monitorDomain()).isEqualTo("game");
        assertThat(tool.readOnly()).isFalse();
        assertThat(tool.critical()).isFalse();
    }

    @Test
    @DisplayName("无对局或异局：SESSION_EXPIRED 如实提示，不编造对局（BR-09）")
    void shouldFailWhenNoOwnSession() {
        assertThat(tool.execute(ctx(), args("hint")).errorType()).isEqualTo("SESSION_EXPIRED");

        store.save(OPENID, GameSession.forCodeBreak("0123", Instant.now()));
        assertThat(tool.execute(ctx(), args("hint")).errorType()).isEqualTo("SESSION_EXPIRED");
    }

    @Test
    @DisplayName("hint：按序揭示递进提示并推进进度；提示尽则 NO_MORE_HINTS")
    void shouldRevealHintsProgressively() {
        lobby.execute(ctx(), lobbyArgs("start", "turtle_soup"));
        int total = totalHints(store.find(OPENID).orElseThrow());

        ToolResult first = tool.execute(ctx(), args("hint"));
        assertThat(first.isSuccess()).isTrue();
        assertThat(first.data().path("hint_index").asInt()).isEqualTo(1);
        assertThat(store.find(OPENID).orElseThrow().hintsUsed()).isEqualTo(1);

        for (int i = 1; i < total; i++) {
            assertThat(tool.execute(ctx(), args("hint")).isSuccess()).isTrue();
        }
        assertThat(store.find(OPENID).orElseThrow().hintsUsed()).isEqualTo(total);
        assertThat(tool.execute(ctx(), args("hint")).errorType()).isEqualTo("NO_MORE_HINTS");
    }

    @Test
    @DisplayName("solve：胜利关局并交还汤底供复盘，会话删除")
    void shouldCloseSessionOnSolve() {
        lobby.execute(ctx(), lobbyArgs("start", "turtle_soup"));

        ToolResult solved = tool.execute(ctx(), args("solve"));

        assertThat(solved.isSuccess()).isTrue();
        assertThat(solved.data().path("result").asText()).isEqualTo("solved");
        assertThat(solved.data().path("solution").asText()).isNotBlank();
        assertThat(store.find(OPENID)).isEmpty();
    }

    @Test
    @DisplayName("giveup：放弃揭底关局，会话删除")
    void shouldRevealSolutionOnGiveup() {
        lobby.execute(ctx(), lobbyArgs("start", "turtle_soup"));

        ToolResult giveup = tool.execute(ctx(), args("giveup"));

        assertThat(giveup.isSuccess()).isTrue();
        assertThat(giveup.data().path("result").asText()).isEqualTo("giveup");
        assertThat(giveup.data().path("solution").asText()).isNotBlank();
        assertThat(store.find(OPENID)).isEmpty();
    }

    private static ToolContext ctx() {
        return ToolContext.of("trace-soup", OPENID, 1L, 0);
    }

    private static JsonNode args(String action) {
        ObjectNode node = JsonUtils.mapper().createObjectNode();
        node.put("action", action);
        return node;
    }

    /** 大厅入参（开局用）。 */
    private static JsonNode lobbyArgs(String action, String game) {
        ObjectNode node = JsonUtils.mapper().createObjectNode();
        node.put("action", action);
        node.put("game", game);
        return node;
    }

    /** 从谜题库回查当前局的总提示数。 */
    private static int totalHints(GameSession session) {
        return com.lumensteward.clawbot.domain.game.TurtleSoupPuzzles.byId(session.puzzleId())
                .map(p -> p.hints().size())
                .orElseThrow();
    }
}
