package com.lumensteward.clawbot.domain.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lumensteward.clawbot.common.util.JsonUtils;
import com.lumensteward.clawbot.domain.game.GameSession;
import com.lumensteward.clawbot.domain.game.GameStore;
import com.lumensteward.clawbot.domain.game.TurtleSoupPuzzles;
import com.lumensteward.clawbot.domain.tool.JsonSchema;
import com.lumensteward.clawbot.domain.tool.Tool;
import com.lumensteward.clawbot.domain.tool.ToolContext;
import com.lumensteward.clawbot.domain.tool.ToolResult;
import com.lumensteward.clawbot.domain.tool.ToolVisibilityContext;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 海龟汤对局工具（迭代 4 W19，name=turtle_soup，会话门控）。
 *
 * <p>仅当该用户<b>海龟汤对局进行中</b>时可见（{@code visibleIn} 读
 * {@code ToolVisibilityContext.activeGame}，W10 机制的第二个真实消费方）。分工：
 * 开局（发汤面、发主持人须知）在 {@link GameLobbyTool}；是非判断由模型依据开局时
 * 交付的汤底与判定要点完成，<b>不经过本工具</b>；本工具只管递进提示（hint）、
 * 判胜关局（solve）与放弃揭底（giveup）三件事。
 *
 * <p>对局缺失（过期/已关局/异局）按结构化失败如实提示，不编造对局（BR-09）。
 * {@code idempotent=false}（hint 推进状态）、{@code critical=false}、{@code readOnly=false}。
 */
@Component
public class TurtleSoupTool implements Tool {

    /** 工具名（与 {@code GameType.TURTLE_SOUP#toolName()} 一致，可见性判据依赖此约定）。 */
    public static final String NAME = "turtle_soup";

    private static final String DESCRIPTION =
            "进行中的海龟汤对局操作：给用户一条递进提示（hint）、用户猜中后胜利关局（solve）、"
                    + "用户放弃时公布汤底（giveup）。仅在海龟汤进行中可见。";

    private static final String PARAMETERS_SCHEMA = """
            {"type":"object","properties":{
              "action":{"type":"string","enum":["hint","solve","giveup"],
                "description":"hint=给一条递进提示；solve=用户已猜中核心事实，胜利关局；giveup=用户放弃，公布汤底"}
            },"required":["action"]}
            """;

    private final GameStore gameStore;
    private final JsonSchema schema;

    /**
     * 构造器注入（G-14）。
     *
     * @param gameStore 桌游会话存储（为 null 时本工具恒不可见）
     */
    public TurtleSoupTool(GameStore gameStore) {
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
        // 只收本玩法专名；「是/不是/无关」类裁判话术不构成动作声明（抽取器只认完成态动词）
        return Set.of("海龟汤", "汤面", "汤底");
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
        if (session == null || session.game() != com.lumensteward.clawbot.domain.game.GameType.TURTLE_SOUP) {
            return ToolResult.failure("SESSION_EXPIRED",
                    "这局海龟汤已经结束或过期了，请让用户到桌游大厅重新开局", false);
        }
        String action = args == null || !args.hasNonNull("action") ? "" : args.get("action").asText();
        return switch (action) {
            case "hint" -> hint(openid, session, start);
            case "solve" -> close(openid, "solved", start);
            case "giveup" -> close(openid, "giveup", start);
            default -> ToolResult.failure("INVALID_ARGS", "action 只支持 hint / solve / giveup", false);
        };
    }

    /** hint：按序揭示一条递进提示（未存住的提示视为没给，宁少勿多）。 */
    private ToolResult hint(String openid, GameSession session, long start) {
        TurtleSoupPuzzles.TurtleSoupPuzzle puzzle = TurtleSoupPuzzles.byId(session.puzzleId()).orElse(null);
        if (puzzle == null) {
            return ToolResult.failure("PUZZLE_MISSING", "对局数据异常（谜题缺失），请到桌游大厅重开一局", false);
        }
        if (session.hintsUsed() >= puzzle.hints().size()) {
            return ToolResult.failure("NO_MORE_HINTS",
                    "提示已全部给出，鼓励用户再试试，或调用 giveup 放弃", false);
        }
        int index = session.hintsUsed();
        GameSession updated = session.withHintsUsed(index + 1);
        if (!gameStore.save(openid, updated)) {
            return ToolResult.failure("STORE_UNAVAILABLE", "游戏服务暂时不可用，提示未能记入进度", true);
        }
        ObjectNode data = JsonUtils.mapper().createObjectNode();
        data.put("hint", puzzle.hints().get(index));
        data.put("hint_index", index + 1);
        data.put("hints_total", puzzle.hints().size());
        data.put("solution", puzzle.solution());
        return ToolResult.success(data, elapsed(start))
                .withMessage("把这条提示讲给用户（可润色语气，不得透露汤底原文），"
                        + "然后引导他继续用是非问题逼近真相。");
    }

    /** solve / giveup：关局（删除会话），交还汤底供复盘。 */
    private ToolResult close(String openid, String result, long start) {
        GameSession session = gameStore.find(openid).orElse(null);
        String puzzleId = session == null ? null : session.puzzleId();
        gameStore.delete(openid);
        TurtleSoupPuzzles.TurtleSoupPuzzle puzzle = TurtleSoupPuzzles.byId(puzzleId).orElse(null);
        if (puzzle == null) {
            return ToolResult.failure("PUZZLE_MISSING", "对局数据异常（谜题缺失），无法复盘", false);
        }
        ObjectNode data = JsonUtils.mapper().createObjectNode();
        data.put("result", result);
        data.put("solution", puzzle.solution());
        data.put("surface", puzzle.surface());
        if (session != null) {
            data.put("hints_used", session.hintsUsed());
        }
        return ToolResult.success(data, elapsed(start))
                .withMessage("solved".equals(result)
                        ? "用户猜中了！请给出祝贺，并完整复盘汤底与推理路径。"
                        : "用户放弃了本局。请公布汤底原文并复盘，鼓励他再开一局。");
    }

    private static long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }
}
