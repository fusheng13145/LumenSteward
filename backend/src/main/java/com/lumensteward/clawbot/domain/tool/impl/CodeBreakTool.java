package com.lumensteward.clawbot.domain.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lumensteward.clawbot.common.util.JsonUtils;
import com.lumensteward.clawbot.domain.game.CodeBreakEngine;
import com.lumensteward.clawbot.domain.game.GameSession;
import com.lumensteward.clawbot.domain.game.GameStore;
import com.lumensteward.clawbot.domain.game.GameType;
import com.lumensteward.clawbot.domain.tool.JsonSchema;
import com.lumensteward.clawbot.domain.tool.Tool;
import com.lumensteward.clawbot.domain.tool.ToolContext;
import com.lumensteward.clawbot.domain.tool.ToolResult;
import com.lumensteward.clawbot.domain.tool.ToolVisibilityContext;
import org.springframework.stereotype.Component;

import java.util.Random;
import java.util.Set;

/**
 * 密码破译对局工具（迭代 4 W19，name=code_break，会话门控）。
 *
 * <p>仅当该用户<b>密码破译对局进行中</b>时可见。密文只存在 {@code GameSession}（Redis），
 * 本工具是唯一接触方：模型转发用户猜测、转述 nA mB 反馈，<b>自始至终拿不到密文</b>——
 * 公平性由存储与工具边界保证，而非提示词自觉。
 *
 * <p>机会规则：共 {@link CodeBreakEngine#MAX_ATTEMPTS} 次，猜测与提示（揭示一位、罚一次）
 * 共享额度；格式非法的猜测不消耗机会；额度用尽后下一次猜测（或 giveup）揭晓答案关局。
 * {@code idempotent=false}、{@code critical=false}、{@code readOnly=false}。
 */
@Component
public class CodeBreakTool implements Tool {

    /** 工具名（与 {@code GameType.CODE_BREAK#toolName()} 一致）。 */
    public static final String NAME = "code_break";

    private static final String DESCRIPTION =
            "进行中的密码破译对局操作：转述用户猜的 4 位数字（guess）、揭示一位数字（hint，耗 1 次机会）、"
                    + "放弃并公布答案（giveup）。仅在密码破译进行中可见。";

    private static final String PARAMETERS_SCHEMA = """
            {"type":"object","properties":{
              "action":{"type":"string","enum":["guess","hint","giveup"],
                "description":"guess=用户报出了 4 位数字猜测；hint=揭示一位（消耗 1 次机会）；giveup=放弃并公布答案"},
              "guess":{"type":"string","description":"用户猜的 4 位互不重复数字，如 0273（guess 时必填）"}
            },"required":["action"]}
            """;

    private final GameStore gameStore;
    private final Random random = new Random();
    private final JsonSchema schema;

    /**
     * 构造器注入（G-14）。
     *
     * @param gameStore 桌游会话存储（为 null 时本工具恒不可见）
     */
    public CodeBreakTool(GameStore gameStore) {
        this.gameStore = gameStore;
        this.schema = JsonSchema.of(PARAMETERS_SCHEMA);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    @Override
    public JsonSchema parametersSchema() {
        return schema;
    }

    @Override
    public boolean idempotent() {
        return false;
    }

    @Override
    public boolean critical() {
        return false;
    }

    @Override
    public boolean readOnly() {
        return false;
    }

    @Override
    public Set<String> claimKeywords() {
        // 刻意不收裸「密码」——与 WiFi 密码、账号密码等日常语义相撞；只收玩法专名
        return Set.of("密码破译");
    }

    @Override
    public String monitorDomain() {
        return "game";
    }

    @Override
    public boolean visibleIn(ToolVisibilityContext context) {
        return gameStore != null && context != null && NAME.equals(context.activeGame());
    }

    @Override
    public ToolResult execute(ToolContext context, JsonNode args) {
        long start = System.currentTimeMillis();
        String openid = context == null ? null : context.openid();
        if (openid == null || openid.isBlank()) {
            return ToolResult.notExecuted("MISSING_OPENID", "缺少用户标识，无法定位对局");
        }
        GameSession session = gameStore.find(openid).orElse(null);
        if (session == null || session.game() != GameType.CODE_BREAK) {
            return ToolResult.failure("SESSION_EXPIRED",
                    "这局密码破译已经结束或过期了，请让用户到桌游大厅重新开局", false);
        }
        String action = args == null || !args.hasNonNull("action") ? "" : args.get("action").asText();
        return switch (action) {
            case "guess" -> guess(openid, session, text(args, "guess"), start);
            case "hint" -> hint(openid, session, start);
            case "giveup" -> reveal(openid, session, "giveup", start);
            default -> ToolResult.failure("INVALID_ARGS", "action 只支持 guess / hint / giveup", false);
        };
    }

    /** guess：合法才消耗机会；中即关局；额度尽即揭底关局；否则存进度回反馈。 */
    private ToolResult guess(String openid, GameSession session, String guess, long start) {
        if (!CodeBreakEngine.isValidGuess(guess)) {
            return ToolResult.failure("INVALID_GUESS",
                    "请让用户报 4 位互不重复的数字（如 0273），本次不计入机会", false);
        }
        if (session.attempts() >= CodeBreakEngine.MAX_ATTEMPTS) {
            return reveal(openid, session, "failed", start);
        }
        CodeBreakEngine.Feedback feedback = CodeBreakEngine.evaluate(session.secretCode(), guess);
        int attempts = session.attempts() + 1;
        if (feedback.exact() == CodeBreakEngine.CODE_LENGTH) {
            return reveal(openid, session.withCodeProgress(attempts, session.codeMask()), "solved", start);
        }
        if (attempts >= CodeBreakEngine.MAX_ATTEMPTS) {
            return reveal(openid, session.withCodeProgress(attempts, session.codeMask()), "failed", start);
        }
        GameSession updated = session.withCodeProgress(attempts, session.codeMask());
        if (!gameStore.save(openid, updated)) {
            return ToolResult.failure("STORE_UNAVAILABLE", "游戏服务暂时不可用，这次猜测未能记入进度", true);
        }
        ObjectNode data = JsonUtils.mapper().createObjectNode();
        data.put("feedback", feedback.exact() + "A" + feedback.misplaced() + "B");
        data.put("exact", feedback.exact());
        data.put("misplaced", feedback.misplaced());
        data.put("attempts_used", attempts);
        data.put("attempts_left", CodeBreakEngine.MAX_ATTEMPTS - attempts);
        data.put("mask", updated.codeMask());
        return ToolResult.success(data, elapsed(start))
                .withMessage("把 nA mB 反馈（A=数字和位置都对，B=数字对位置不对）"
                        + "和剩余机会讲给用户，鼓励他继续推理。");
    }

    /** hint：随机揭示一位未揭示数字，罚 1 次机会。 */
    private ToolResult hint(String openid, GameSession session, long start) {
        if (session.attempts() >= CodeBreakEngine.MAX_ATTEMPTS) {
            return ToolResult.failure("NO_ATTEMPTS_LEFT",
                    "机会已用尽，只能赌最后一次猜测或调用 giveup 揭晓答案", false);
        }
        String newMask = CodeBreakEngine.revealOne(session.secretCode(), session.codeMask(), random);
        int attempts = session.attempts() + 1;
        GameSession updated = session.withCodeProgress(attempts, newMask);
        if (!gameStore.save(openid, updated)) {
            return ToolResult.failure("STORE_UNAVAILABLE", "游戏服务暂时不可用，提示未能记入进度", true);
        }
        ObjectNode data = JsonUtils.mapper().createObjectNode();
        data.put("mask", newMask);
        data.put("attempts_used", attempts);
        data.put("attempts_left", CodeBreakEngine.MAX_ATTEMPTS - attempts);
        return ToolResult.success(data, elapsed(start))
                .withMessage("把最新掩码（· 为未揭示位）和剩余机会讲给用户。");
    }

    /** 关局并揭晓答案（solved / failed / giveup 共用）。 */
    private ToolResult reveal(String openid, GameSession session, String result, long start) {
        gameStore.delete(openid);
        ObjectNode data = JsonUtils.mapper().createObjectNode();
        data.put("result", result);
        data.put("secret", session.secretCode());
        data.put("attempts_used", session.attempts());
        data.put("mask", session.codeMask());
        return ToolResult.success(data, elapsed(start))
                .withMessage(switch (result) {
                    case "solved" -> "用户猜中了！请给出祝贺，并公布答案与全程用次。";
                    case "failed" -> "机会用尽仍未猜中。请公布答案，复盘 nA mB 的推理线索，鼓励他再开一局。";
                    default -> "用户放弃了本局。请公布答案并简单复盘。";
                });
    }

    private static String text(JsonNode args, String field) {
        if (args == null || !args.hasNonNull(field)) {
            return null;
        }
        return args.get(field).asText();
    }

    private static long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }
}
