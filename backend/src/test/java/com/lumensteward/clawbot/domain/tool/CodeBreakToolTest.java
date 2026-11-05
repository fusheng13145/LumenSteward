package com.lumensteward.clawbot.domain.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lumensteward.clawbot.common.util.JsonUtils;
import com.lumensteward.clawbot.domain.game.GameSession;
import com.lumensteward.clawbot.domain.tool.impl.CodeBreakTool;
import com.lumensteward.clawbot.support.InMemoryGameStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 密码破译对局工具测试（迭代 4 W19）：密文不出服务器、nA mB 反馈、机会额度与关局路径。
 */
class CodeBreakToolTest {

    private static final String OPENID = "openid-code";
    private static final String SECRET = "0123";

    private final InMemoryGameStore store = new InMemoryGameStore();
    private final CodeBreakTool tool = new CodeBreakTool(store);

    @Test
    @DisplayName("可见性：仅当本用户密码破译对局进行中（activeGame=code_break）才可见")
    void shouldBeVisibleOnlyWhenOwnSessionActive() {
        assertThat(tool.visibleIn(new ToolVisibilityContext(OPENID, false, "code_break"))).isTrue();
        assertThat(tool.visibleIn(new ToolVisibilityContext(OPENID, false, "turtle_soup"))).isFalse();
        assertThat(tool.visibleIn(new ToolVisibilityContext(OPENID, false, null))).isFalse();
        assertThat(new CodeBreakTool(null).visibleIn(
                new ToolVisibilityContext(OPENID, false, "code_break"))).isFalse();
    }

    @Test
    @DisplayName("自述元数据：判定词只收专名「密码破译」（不收裸「密码」防语义相撞）、归因域 game")
    void shouldSelfDescribeMetadata() {
        assertThat(tool.name()).isEqualTo("code_break");
        assertThat(tool.claimKeywords()).containsExactly("密码破译");
        assertThat(tool.monitorDomain()).isEqualTo("game");
        assertThat(tool.readOnly()).isFalse();
        assertThat(tool.critical()).isFalse();
    }

    @Test
    @DisplayName("非法猜测：INVALID_GUESS 不消耗机会，会话不动")
    void shouldNotConsumeAttemptOnMalformedGuess() {
        seed();
        for (String bad : new String[]{"01", "01a3", "0113", null}) {
            assertThat(tool.execute(ctx(), guessArgs(bad)).errorType()).isEqualTo("INVALID_GUESS");
        }
        assertThat(store.find(OPENID).orElseThrow().attempts()).isZero();
    }

    @Test
    @DisplayName("未中猜测：nA mB 反馈正确（0123 vs 1034 → 0A3B），机会推进并落库")
    void shouldReturnCorrectFeedbackAndPersistAttempt() {
        seed();

        ToolResult result = tool.execute(ctx(), guessArgs("1034"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.data().path("exact").asInt()).isZero();
        assertThat(result.data().path("misplaced").asInt()).isEqualTo(3);
        assertThat(result.data().path("feedback").asText()).isEqualTo("0A3B");
        assertThat(result.data().path("attempts_left").asInt()).isEqualTo(7);
        assertThat(store.find(OPENID).orElseThrow().attempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("猜中：即刻关局揭晓答案，会话删除")
    void shouldSolveAndRevealOnCorrectGuess() {
        seed();

        ToolResult result = tool.execute(ctx(), guessArgs("0123"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.data().path("result").asText()).isEqualTo("solved");
        assertThat(result.data().path("secret").asText()).isEqualTo(SECRET);
        assertThat(store.find(OPENID)).isEmpty();
    }

    @Test
    @DisplayName("机会用尽：下一次猜测按失败揭底关局（result=failed）")
    void shouldFailAndRevealWhenAttemptsExhausted() {
        store.save(OPENID, GameSession.forCodeBreak(SECRET, Instant.now()).withCodeProgress(8, "····"));

        ToolResult result = tool.execute(ctx(), guessArgs("4567"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.data().path("result").asText()).isEqualTo("failed");
        assertThat(result.data().path("secret").asText()).isEqualTo(SECRET);
        assertThat(store.find(OPENID)).isEmpty();
    }

    @Test
    @DisplayName("hint：随机揭示一位（点数少一）并罚 1 次机会；额度尽则拒绝提示")
    void shouldRevealOneDigitWithPenaltyAndRefuseWhenExhausted() {
        seed();
        int dotsBefore = countDots(store.find(OPENID).orElseThrow().codeMask());

        ToolResult hint = tool.execute(ctx(), args("hint", null));
        assertThat(hint.isSuccess()).isTrue();
        assertThat(countDots(hint.data().path("mask").asText())).isEqualTo(dotsBefore - 1);
        assertThat(hint.data().path("attempts_left").asInt()).isEqualTo(7);
        assertThat(store.find(OPENID).orElseThrow().attempts()).isEqualTo(1);

        store.save(OPENID, GameSession.forCodeBreak(SECRET, Instant.now()).withCodeProgress(8, "0···"));
        assertThat(tool.execute(ctx(), args("hint", null)).errorType()).isEqualTo("NO_ATTEMPTS_LEFT");
    }

    @Test
    @DisplayName("giveup：放弃揭底关局，会话删除")
    void shouldRevealOnGiveup() {
        seed();

        ToolResult result = tool.execute(ctx(), args("giveup", null));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.data().path("result").asText()).isEqualTo("giveup");
        assertThat(result.data().path("secret").asText()).isEqualTo(SECRET);
        assertThat(store.find(OPENID)).isEmpty();
    }

    private void seed() {
        store.save(OPENID, GameSession.forCodeBreak(SECRET, Instant.now()));
    }

    private static int countDots(String mask) {
        return (int) mask.chars().filter(c -> c == '·').count();
    }

    private static ToolContext ctx() {
        return ToolContext.of("trace-code", OPENID, 1L, 0);
    }

    private static JsonNode guessArgs(String guess) {
        ObjectNode node = JsonUtils.mapper().createObjectNode();
        node.put("action", "guess");
        if (guess != null) {
            node.put("guess", guess);
        }
        return node;
    }

    private static JsonNode args(String action, String guess) {
        ObjectNode node = JsonUtils.mapper().createObjectNode();
        node.put("action", action);
        if (guess != null) {
            node.put("guess", guess);
        }
        return node;
    }
}
