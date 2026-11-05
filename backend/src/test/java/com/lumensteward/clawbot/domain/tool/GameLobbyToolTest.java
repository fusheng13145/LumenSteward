package com.lumensteward.clawbot.domain.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.lumensteward.clawbot.domain.game.GameSession;
import com.lumensteward.clawbot.domain.game.GameType;
import com.lumensteward.clawbot.domain.tool.impl.GameLobbyTool;
import com.lumensteward.clawbot.support.InMemoryGameStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 桌游大厅工具测试（迭代 4 W19）：可见性、开局/重入/换局/关局/列表与存储降级路径。
 */
class GameLobbyToolTest {

    private static final String OPENID = "openid-lobby";
    private final InMemoryGameStore store = new InMemoryGameStore();
    private final GameLobbyTool tool = new GameLobbyTool(store);

    @Test
    @DisplayName("可见性：有存储即恒可见；存储为 null（standalone 构造）恒不可见")
    void shouldBeVisibleWhenStoreWired() {
        assertThat(tool.visibleIn(new ToolVisibilityContext(OPENID, false, null))).isTrue();
        assertThat(tool.visibleIn(new ToolVisibilityContext(OPENID, true, "turtle_soup"))).isTrue();
        assertThat(new GameLobbyTool(null).visibleIn(ToolVisibilityContext.of(OPENID))).isFalse();
    }

    @Test
    @DisplayName("自述元数据：判定词收专名、归因域 game、非只读非幂等非关键")
    void shouldSelfDescribeMetadata() {
        assertThat(tool.name()).isEqualTo("game_lobby");
        assertThat(tool.claimKeywords()).containsExactlyInAnyOrder("桌游", "海龟汤", "密码破译", "模拟人生");
        assertThat(tool.monitorDomain()).isEqualTo("game");
        assertThat(tool.readOnly()).isFalse();
        assertThat(tool.idempotent()).isFalse();
        assertThat(tool.critical()).isFalse();
    }

    @Test
    @DisplayName("缺 openid 或非法入参：结构化失败，不落任何会话")
    void shouldFailStructurallyOnBadInput() {
        assertThat(tool.execute(null, args("start", "turtle_soup")).errorType()).isEqualTo("MISSING_OPENID");
        assertThat(tool.execute(ctx(), args("start", "斗地主")).errorType()).isEqualTo("INVALID_ARGS");
        assertThat(tool.execute(ctx(), args("dance", null)).errorType()).isEqualTo("INVALID_ARGS");

        assertThat(store.find(OPENID)).isEmpty();
    }

    @Test
    @DisplayName("开局海龟汤：会话落库、结果附汤面与主持人须知（汤底只进 data 不进 message）")
    void shouldStartTurtleSoupWithHostBriefing() {
        ToolResult result = tool.execute(ctx(), args("start", "海龟汤"));

        assertThat(result.isSuccess()).isTrue();
        GameSession session = store.find(OPENID).orElseThrow();
        assertThat(session.game()).isEqualTo(GameType.TURTLE_SOUP);
        assertThat(session.puzzleId()).startsWith("ts");

        JsonNode data = result.data();
        assertThat(data.path("surface").asText()).isNotBlank();
        assertThat(data.path("solution").asText()).isNotBlank();
        assertThat(data.path("host_note").asText()).contains("不得直接说出汤底");
        // 汤底允许进 data（模型主持人知识），但不得混入 message（message 更贴近用户可见回复）
        assertThat(result.message()).doesNotContain(data.path("solution").asText());
    }

    @Test
    @DisplayName("同玩法重入：不换题不重置，返回当前进度并提示继续对局")
    void shouldKeepSameSessionWhenRestartingSameGame() {
        tool.execute(ctx(), args("start", "turtle_soup"));
        GameSession first = store.find(OPENID).orElseThrow();

        ToolResult again = tool.execute(ctx(), args("start", "turtle_soup"));

        assertThat(again.isSuccess()).isTrue();
        assertThat(again.message()).contains("还在进行中");
        assertThat(store.find(OPENID).orElseThrow()).isSameAs(first);
    }

    @Test
    @DisplayName("异玩法开局：自动结束旧局并在结果中说明，新局覆盖")
    void shouldEndPreviousGameWhenSwitching() {
        tool.execute(ctx(), args("start", "code_break"));
        assertThat(store.find(OPENID).orElseThrow().game()).isEqualTo(GameType.CODE_BREAK);

        ToolResult switched = tool.execute(ctx(), args("start", "模拟人生"));

        assertThat(switched.isSuccess()).isTrue();
        assertThat(switched.data().path("previous_game_ended").asText()).isEqualTo("密码破译");
        assertThat(store.find(OPENID).orElseThrow().game()).isEqualTo(GameType.LIFE_SIM);
    }

    @Test
    @DisplayName("开局密码破译：密文只进会话，data 与 message 均不得泄露密文")
    void shouldKeepSecretOutOfModelVisibleFields() {
        ToolResult result = tool.execute(ctx(), args("start", "code_break"));

        assertThat(result.isSuccess()).isTrue();
        GameSession session = store.find(OPENID).orElseThrow();
        assertThat(session.game()).isEqualTo(GameType.CODE_BREAK);
        assertThat(session.secretCode()).hasSize(4);
        assertThat(result.data().has("secret")).isFalse();
        assertThat(result.message()).contains("4 位互不重复").doesNotContain(session.secretCode());
    }

    @Test
    @DisplayName("quit：无局返回 NO_ACTIVE_GAME；有局删除并附小结")
    void shouldQuitActiveGame() {
        assertThat(tool.execute(ctx(), args("quit", null)).errorType()).isEqualTo("NO_ACTIVE_GAME");

        tool.execute(ctx(), args("start", "life_sim"));
        ToolResult quit = tool.execute(ctx(), args("quit", null));

        assertThat(quit.isSuccess()).isTrue();
        assertThat(quit.data().path("result").asText()).isEqualTo("quit");
        assertThat(quit.data().path("summary").path("name").asText()).isEqualTo("模拟人生");
        assertThat(store.find(OPENID)).isEmpty();
    }

    @Test
    @DisplayName("list：三种玩法齐备，active 随有无对局变化")
    void shouldListGamesWithActiveSession() {
        ToolResult idle = tool.execute(ctx(), args("list", null));
        assertThat(idle.data().path("games")).hasSize(3);
        assertThat(idle.data().path("active").isNull()).isTrue();

        tool.execute(ctx(), args("start", "turtle_soup"));
        ToolResult active = tool.execute(ctx(), args("list", null));
        assertThat(active.data().path("active").path("game").asText()).isEqualTo("turtle_soup");
    }

    private static ToolContext ctx() {
        return ToolContext.of("trace-lobby", OPENID, 1L, 0);
    }

    private static JsonNode args(String action, String game) {
        com.fasterxml.jackson.databind.node.ObjectNode node =
                com.lumensteward.clawbot.common.util.JsonUtils.mapper().createObjectNode();
        if (action != null) {
            node.put("action", action);
        }
        if (game != null) {
            node.put("game", game);
        }
        return node;
    }
}
