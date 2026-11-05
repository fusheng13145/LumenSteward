package com.lumensteward.clawbot.domain.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lumensteward.clawbot.common.util.JsonUtils;
import com.lumensteward.clawbot.domain.game.GameSession;
import com.lumensteward.clawbot.domain.game.GameStore;
import com.lumensteward.clawbot.domain.game.GameType;
import com.lumensteward.clawbot.domain.game.LifeCharacter;
import com.lumensteward.clawbot.domain.game.LifeSimEngine;
import com.lumensteward.clawbot.domain.tool.JsonSchema;
import com.lumensteward.clawbot.domain.tool.Tool;
import com.lumensteward.clawbot.domain.tool.ToolContext;
import com.lumensteward.clawbot.domain.tool.ToolResult;
import com.lumensteward.clawbot.domain.tool.ToolVisibilityContext;
import org.springframework.stereotype.Component;

import java.util.Random;
import java.util.Set;

/**
 * 模拟人生对局工具（迭代 4 W19，name=life_sim，会话门控）。
 *
 * <p>仅当该用户<b>模拟人生对局进行中</b>时可见。分工：开局（建角色）在
 * {@link GameLobbyTool}；本工具只做两件事——把玩家这一回合的人生选择交给引擎结算
 * （live），或汇报角色当前状态（status）。引擎返回的是<b>事实性结果</b>（事件 + 属性增量 +
 * 是否结局），叙事由模型完成，决定权始终在玩家手里。
 *
 * <p>结局（健康归零 / 年满三十）即关局；对局缺失按结构化失败如实提示（BR-09）。
 * {@code idempotent=false}、{@code critical=false}、{@code readOnly=false}。
 */
@Component
public class LifeSimTool implements Tool {

    /** 工具名（与 {@code GameType.LIFE_SIM#toolName()} 一致）。 */
    public static final String NAME = "life_sim";

    private static final String DESCRIPTION =
            "进行中的模拟人生对局操作：把玩家这一年的人生选择交给你来结算（live）"
                    + "或查看角色当前状态（status）。仅在模拟人生进行中可见。";

    private static final String PARAMETERS_SCHEMA = """
            {"type":"object","properties":{
              "action":{"type":"string","enum":["live","status"],
                "description":"live=结算玩家这一回合的人生选择（须带 choice）；status=汇报角色当前状态"},
              "choice":{"type":"string","description":"玩家这一年的选择/行动，如「认真工作」「出门旅行」（live 时必填）"}
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
    public LifeSimTool(GameStore gameStore) {
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
        // 「角色」会出现在本工具结果驱动的叙事句里，作为支撑词保留；泛词（人生）不收
        return Set.of("模拟人生", "角色");
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
        if (session == null || session.game() != GameType.LIFE_SIM) {
            return ToolResult.failure("SESSION_EXPIRED",
                    "这局模拟人生已经结束或过期了，请让用户到桌游大厅重新开局", false);
        }
        String action = args == null || !args.hasNonNull("action") ? "" : args.get("action").asText();
        return switch (action) {
            case "live" -> live(openid, session, text(args, "choice"), start);
            case "status" -> status(session, start);
            default -> ToolResult.failure("INVALID_ARGS", "action 只支持 live / status", false);
        };
    }

    /** live：引擎结算一回合；结局即关局（未存住的回合视为没发生，宁少勿多）。 */
    private ToolResult live(String openid, GameSession session, String choice, long start) {
        if (choice == null || choice.isBlank()) {
            return ToolResult.failure("INVALID_ARGS",
                    "choice 不能为空：请先问玩家这一年想做什么，再把他的选择转述过来", false);
        }
        LifeSimEngine.LifeOutcome outcome =
                LifeSimEngine.live(session.character(), choice, random);
        if (outcome.ended()) {
            gameStore.delete(openid);
        } else if (!gameStore.save(openid, session.withCharacter(outcome.after()))) {
            return ToolResult.failure("STORE_UNAVAILABLE", "游戏服务暂时不可用，这一年未能记入进度", true);
        }
        ObjectNode data = JsonUtils.mapper().createObjectNode();
        data.put("choice", choice);
        data.put("summary", outcome.summary());
        data.put("ended", outcome.ended());
        if (outcome.ending() != null) {
            data.put("ending", outcome.ending());
        }
        data.set("character", sheet(outcome.after()));
        return ToolResult.success(data, elapsed(start))
                .withMessage(outcome.ended()
                        ? "把结算结果讲成故事，收在结局上；可邀请用户再开一局新的人生。"
                        : "把结算结果讲成故事（年份 " + outcome.after().age() + " 岁），"
                        + "然后问玩家下一年想做什么；不要替玩家做新决定。");
    }

    /** status：汇报角色当前状态。 */
    private ToolResult status(GameSession session, long start) {
        ObjectNode data = JsonUtils.mapper().createObjectNode();
        data.set("character", sheet(session.character()));
        data.put("ended", false);
        return ToolResult.success(data, elapsed(start))
                .withMessage("把角色当前状态讲给用户，然后问他这一年想做什么。");
    }

    private static ObjectNode sheet(LifeCharacter character) {
        ObjectNode node = JsonUtils.mapper().createObjectNode();
        node.put("age", character.age());
        node.put("health", character.health());
        node.put("mind", character.mind());
        node.put("wealth", character.wealth());
        node.put("social", character.social());
        node.put("turn", character.turn());
        return node;
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
