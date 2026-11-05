package com.lumensteward.clawbot.domain.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lumensteward.clawbot.common.util.JsonUtils;
import com.lumensteward.clawbot.common.util.MaskUtils;
import com.lumensteward.clawbot.domain.game.CodeBreakEngine;
import com.lumensteward.clawbot.domain.game.GameSession;
import com.lumensteward.clawbot.domain.game.GameStore;
import com.lumensteward.clawbot.domain.game.GameType;
import com.lumensteward.clawbot.domain.game.LifeSimEngine;
import com.lumensteward.clawbot.domain.game.TurtleSoupPuzzles;
import com.lumensteward.clawbot.domain.tool.JsonSchema;
import com.lumensteward.clawbot.domain.tool.Tool;
import com.lumensteward.clawbot.domain.tool.ToolContext;
import com.lumensteward.clawbot.domain.tool.ToolResult;
import com.lumensteward.clawbot.domain.tool.ToolVisibilityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Random;
import java.util.Set;

/**
 * 桌游大厅工具（迭代 4 W19 单人桌游技能，name=game_lobby）。
 *
 * <p><b>W19 的恒可见入口</b>：三类桌游（海龟汤 / 密码破译 / 模拟人生，D2 的 P1「桌游 ≥3 类」）
 * 的开局、关局与玩法列表都从这里进；对局进行中的每回合操作由三个<b>会话门控</b>工具
 * （{@code turtle_soup} / {@code code_break} / {@code life_sim}）承接——它们仅在对应玩法
 * 进行中可见（W10 可见性机制 + {@code ToolVisibilityContext.activeGame}），使广度扩张不突破
 * P2「每轮下发 Schema ≤8」。
 *
 * <p>单用户同时至多一局：开新局时若已有进行中局则自动结束旧局并在结果中说明；
 * 存储不可用按结构化失败如实提示（fail-open，不影响主对话链路）。
 * {@code idempotent=false}（重开即新局）、{@code critical=false}、{@code readOnly=false}
 * （写游戏会话，回放干跑不得触碰）。
 */
@Component
public class GameLobbyTool implements Tool {

    /** 工具名（全局唯一，FR-23）。 */
    public static final String NAME = "game_lobby";

    private static final Logger log = LoggerFactory.getLogger(GameLobbyTool.class);

    private static final String DESCRIPTION =
            "桌游大厅：开始或结束一局小游戏，或查看玩法列表。现有海龟汤（情境猜谜）、"
                    + "密码破译（猜 4 位数字）、模拟人生（文字人生模拟）三种。用户想玩游戏时用它。";

    private static final String PARAMETERS_SCHEMA = """
            {"type":"object","properties":{
              "action":{"type":"string","enum":["start","quit","list"],
                "description":"start=开始新局（须带 game）；quit=结束当前局并给小结；list=列出可玩游戏与当前对局"},
              "game":{"type":"string","enum":["turtle_soup","code_break","life_sim"],
                "description":"要开始的游戏（start 时必填）"}
            },"required":["action"]}
            """;

    private final GameStore gameStore;
    private final Random random = new Random();
    private final JsonSchema schema;

    /**
     * 构造器注入（G-14）。
     *
     * @param gameStore 桌游会话存储（为 null 时本工具恒不可见，standalone 构造兜底）
     */
    public GameLobbyTool(GameStore gameStore) {
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
        // 只收专名：三个玩法名都会出现在本工具的开局/列表文案里；泛词（游戏/玩）不收，
        // 避免闲聊提及即构成动作声明（BR-04 误报面）
        return Set.of("桌游", "海龟汤", "密码破译", "模拟人生");
    }

    @Override
    public String monitorDomain() {
        return "game";
    }

    @Override
    public boolean visibleIn(ToolVisibilityContext context) {
        return gameStore != null;
    }

    @Override
    public ToolResult execute(ToolContext context, JsonNode args) {
        long start = System.currentTimeMillis();
        String openid = context == null ? null : context.openid();
        if (openid == null || openid.isBlank()) {
            return ToolResult.notExecuted("MISSING_OPENID", "缺少用户标识，无法定位对局");
        }
        String action = text(args, "action");
        return switch (action == null ? "" : action) {
            case "start" -> start(openid, text(args, "game"), start);
            case "quit" -> quit(openid, start);
            case "list" -> list(openid, start);
            default -> ToolResult.failure("INVALID_ARGS", "action 只支持 start / quit / list", false);
        };
    }

    /** start：开新局（同玩法已在局中则回当前进度；异玩法自动结束旧局）。 */
    private ToolResult start(String openid, String gameInput, long start) {
        java.util.Optional<GameType> type = GameType.byInput(gameInput);
        if (type.isEmpty()) {
            return ToolResult.failure("INVALID_ARGS",
                    "game 必须是 turtle_soup / code_break / life_sim 之一", false);
        }
        GameType game = type.get();
        GameSession existing = gameStore.find(openid).orElse(null);
        if (existing != null && existing.game() == game) {
            return ToolResult.success(statusOf(existing), elapsed(start))
                    .withMessage("本局 " + game.displayName() + " 还在进行中（进度见 data），"
                            + "请继续当前对局；用户若想重开，请先 quit 再 start。");
        }
        GameSession fresh = switch (game) {
            case TURTLE_SOUP -> GameSession.forTurtleSoup(
                    TurtleSoupPuzzles.draw(random).id(), Instant.now());
            case CODE_BREAK -> GameSession.forCodeBreak(
                    CodeBreakEngine.generateSecret(random), Instant.now());
            case LIFE_SIM -> GameSession.forLifeSim(
                    LifeSimEngine.create(random), Instant.now());
        };
        if (!gameStore.save(openid, fresh)) {
            log.info("桌游开局保存失败 openid={}", MaskUtils.openid(openid));
            return ToolResult.failure("STORE_UNAVAILABLE", "游戏服务暂时不可用，请稍后再试", true);
        }
        return ToolResult.success(introOf(fresh, existing), elapsed(start))
                .withMessage(startMessage(fresh, existing));
    }

    /** quit：结束当前局并给小结。 */
    private ToolResult quit(String openid, long start) {
        GameSession existing = gameStore.find(openid).orElse(null);
        if (existing == null) {
            return ToolResult.failure("NO_ACTIVE_GAME", "当前没有进行中的游戏", false);
        }
        gameStore.delete(openid);
        ObjectNode data = JsonUtils.mapper().createObjectNode();
        data.put("result", "quit");
        data.put("game", existing.game().displayName());
        data.set("summary", statusOf(existing));
        return ToolResult.success(data, elapsed(start))
                .withMessage("本局 " + existing.game().displayName() + " 已收摊，"
                        + "请结合 summary 给用户一个简短的本局小结。");
    }

    /** list：玩法列表 + 当前对局。 */
    private ToolResult list(String openid, long start) {
        GameSession existing = gameStore.find(openid).orElse(null);
        ObjectNode data = JsonUtils.mapper().createObjectNode();
        ArrayNode games = data.putArray("games");
        for (GameType type : GameType.values()) {
            ObjectNode item = games.addObject();
            item.put("id", type.toolName());
            item.put("name", type.displayName());
            item.put("how", switch (type) {
                case TURTLE_SOUP -> "我出一段离奇情境（汤面），你用是非问题逼近真相";
                case CODE_BREAK -> "我藏了一个 4 位不重复数字的密码，你猜我报 nA mB，共 8 次机会";
                case LIFE_SIM -> "从 18 岁起每年做一个选择，我把人生推进到三十而立";
            });
        }
        data.set("active", existing == null ? null : statusOf(existing));
        return ToolResult.success(data, elapsed(start))
                .withMessage(existing == null
                        ? "当前没有进行中的对局。请向用户介绍三种玩法并问他想玩哪个。"
                        : "当前有一局 " + existing.game().displayName() + " 进行中（进度见 data）。");
    }

    /** 当前对局的进度快照（list / 重入 / quit 共用）。 */
    private ObjectNode statusOf(GameSession session) {
        ObjectNode node = JsonUtils.mapper().createObjectNode();
        node.put("game", session.game().toolName());
        node.put("name", session.game().displayName());
        switch (session.game()) {
            case TURTLE_SOUP -> {
                TurtleSoupPuzzles.TurtleSoupPuzzle puzzle = TurtleSoupPuzzles.byId(session.puzzleId())
                        .orElse(null);
                node.put("surface", puzzle == null ? null : puzzle.surface());
                node.set("solution", puzzle == null ? null
                        : JsonUtils.mapper().valueToTree(puzzle.solution()));
                node.put("hints_used", session.hintsUsed());
                node.put("hints_total", puzzle == null ? 0 : puzzle.hints().size());
            }
            case CODE_BREAK -> {
                node.put("mask", session.codeMask());
                node.put("attempts_used", session.attempts());
                node.put("attempts_left", Math.max(0, CodeBreakEngine.MAX_ATTEMPTS - session.attempts()));
            }
            case LIFE_SIM -> {
                if (session.character() != null) {
                    node.put("age", session.character().age());
                    node.put("health", session.character().health());
                    node.put("mind", session.character().mind());
                    node.put("wealth", session.character().wealth());
                    node.put("social", session.character().social());
                    node.put("turn", session.character().turn());
                }
            }
        }
        return node;
    }

    /** 开局结果 data：附各玩法给模型主持人的「内部须知」。 */
    private ObjectNode introOf(GameSession fresh, GameSession previous) {
        ObjectNode data = JsonUtils.mapper().createObjectNode();
        data.put("game", fresh.game().toolName());
        if (previous != null && previous.game() != fresh.game()) {
            data.put("previous_game_ended", previous.game().displayName());
        }
        switch (fresh.game()) {
            case TURTLE_SOUP -> {
                TurtleSoupPuzzles.TurtleSoupPuzzle puzzle = TurtleSoupPuzzles.byId(fresh.puzzleId())
                        .orElseThrow();
                data.put("puzzle_id", puzzle.id());
                data.put("surface", puzzle.surface());
                data.put("solution", puzzle.solution());
                ArrayNode keyPoints = data.putArray("key_points");
                puzzle.keyPoints().forEach(keyPoints::add);
                data.put("hints_total", puzzle.hints().size());
                data.put("host_note", "汤底与判定要点只许你我知道，任何情况下都不得直接说出汤底原文；"
                        + "用户的是非问题按判定要点答「是」「不是」或「与此无关」，答不出判「无关」；"
                        + "用户猜中核心事实时调用 turtle_soup 的 solve 关局。");
            }
            case CODE_BREAK -> {
                data.put("code_length", CodeBreakEngine.CODE_LENGTH);
                data.put("max_attempts", CodeBreakEngine.MAX_ATTEMPTS);
                data.put("host_note", "密文由系统保管，你拿不到也不需要拿到；请催用户直接报 4 位数字，"
                        + "你原样转述给 code_break 工具并把 nA mB 反馈翻译给用户。");
            }
            case LIFE_SIM -> {
                if (fresh.character() != null) {
                    data.put("age", fresh.character().age());
                    data.put("health", fresh.character().health());
                    data.put("mind", fresh.character().mind());
                    data.put("wealth", fresh.character().wealth());
                    data.put("social", fresh.character().social());
                }
                data.put("host_note", "每回合把玩家的人生选择转述给 life_sim 工具，"
                        + "工具会返回这一年的事实性结果；你只负责把结果讲成故事，不要替玩家做新决定。");
            }
        }
        return data;
    }

    /** 开局消息（面向模型的下一步指令；刻意避开完成态动词，防 BR-04 误报）。 */
    private String startMessage(GameSession fresh, GameSession previous) {
        String prefix = previous != null && previous.game() != fresh.game()
                ? "上一局 " + previous.game().displayName() + " 已替用户结束。" : "";
        return switch (fresh.game()) {
            case TURTLE_SOUP -> prefix + "海龟汤开局成功。请把 data.surface 原样讲给用户，"
                    + "然后引导他用是非问题来猜；汤底（data.solution）绝对不能出现在回复里。";
            case CODE_BREAK -> prefix + "密码破译开局成功。告诉用户：密码是一个 4 位互不重复的数字，"
                    + "直接报数字来猜，每次会得到 nA mB 反馈，共有 " + CodeBreakEngine.MAX_ATTEMPTS + " 次机会。";
            case LIFE_SIM -> prefix + "模拟人生开局成功。请用一两句话把 18 岁的角色近况讲给用户"
                    + "（属性见 data），然后问他这一年想做什么。";
        };
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
