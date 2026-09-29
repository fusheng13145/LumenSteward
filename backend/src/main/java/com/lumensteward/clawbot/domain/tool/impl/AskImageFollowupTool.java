package com.lumensteward.clawbot.domain.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lumensteward.clawbot.common.util.JsonUtils;
import com.lumensteward.clawbot.domain.context.RecentImageStore;
import com.lumensteward.clawbot.domain.model.RecentImage;
import com.lumensteward.clawbot.domain.tool.JsonSchema;
import com.lumensteward.clawbot.domain.tool.Tool;
import com.lumensteward.clawbot.domain.tool.ToolContext;
import com.lumensteward.clawbot.domain.tool.ToolResult;
import com.lumensteward.clawbot.domain.tool.ToolVisibilityContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * 图片追问工具（迭代 4 W11 识图缓存与追问续接，name=ask_image）。
 *
 * <p><b>W10 机制的首个真实消费者</b>：仅在用户确有可追问图片（识图缓存存在）时出现在
 * 函数 Schema 里（{@link #visibleIn(ToolVisibilityContext)}）；被调用时返回缓存的识图结论，
 * 使模型<b>不要求用户重发图片</b>即可回答追问（D1 场景 S2 的核心观测）。
 *
 * <p>本工具不发起任何视觉调用：结论来自 {@link RecentImageStore} 的最近一次成功识图；
 * 缓存缺失（如下发与执行之间过期）按结构化失败返回并提示重新发送（BR-09 不编造）。
 * {@code idempotent=true}（只读）、{@code critical=false}、{@code readOnly=true}（可回放）。
 */
@Component
public class AskImageFollowupTool implements Tool {

    /** 工具名（全局唯一，FR-23）。 */
    public static final String NAME = "ask_image";

    private static final Logger log = LoggerFactory.getLogger(AskImageFollowupTool.class);

    private static final String DESCRIPTION =
            "回答用户对刚发过的图片的追问（如品种、年龄、颜色、图里的文字等），"
                    + "无需用户重发图片。仅在确有可追问图片时可见。";

    private static final String PARAMETERS_SCHEMA = """
            {"type":"object","properties":{
              "question":{"type":"string","description":"用户追问的问题（可选，用于组织回答）"}
            },"required":[]}
            """;

    private final RecentImageStore recentImageStore;
    private final JsonSchema schema;

    /**
     * 构造器注入（G-14）。
     *
     * @param recentImageStore 最近识图缓存（为 null 时本工具恒不可见，standalone 构造兜底）
     */
    public AskImageFollowupTool(RecentImageStore recentImageStore) {
        this.recentImageStore = recentImageStore;
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
        return true;
    }

    @Override
    public boolean critical() {
        return false;
    }

    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public Set<String> claimKeywords() {
        // 与 recognize_image 同语义：本工具返回的就是「图片识别结论」，回复声称
        // 「已识别/已看图」由任一图片类工具的执行记录支撑均属一致
        return Set.of("识别", "图片");
    }

    @Override
    public String monitorDomain() {
        return "image_followup";
    }

    @Override
    public boolean visibleIn(ToolVisibilityContext context) {
        return recentImageStore != null && context != null && context.imageCachePresent();
    }

    @Override
    public ToolResult execute(ToolContext context, JsonNode args) {
        long start = System.currentTimeMillis();
        String openid = context == null ? null : context.openid();
        if (openid == null || openid.isBlank()) {
            return ToolResult.notExecuted("MISSING_OPENID", "缺少用户标识，无法定位识图缓存");
        }
        return recentImageStore.find(openid)
                .<ToolResult>map(image -> success(openid, image, text(args, "question"), start))
                .orElseGet(() -> {
                    // 下发时可见、执行时缓存已消失（过期/被清）：如实告知，不编造（BR-09）
                    log.info("图片追问时缓存已不存在 openid 脱敏={}", com.lumensteward.clawbot.common.util.MaskUtils.openid(openid));
                    return ToolResult.failure("CACHE_EXPIRED", "图片信息已过期，请重新发送图片", false);
                });
    }

    private ToolResult success(String openid, RecentImage image, String question, long start) {
        ObjectNode data = JsonUtils.mapper().createObjectNode();
        data.put("description", image.description());
        data.put("scene", image.scene());
        data.put("confidence", image.confidence());
        data.put("recognized_at", image.recognizedAt() == null ? null : image.recognizedAt().toString());
        data.put("age_note", describeAge(image.recognizedAt()));
        if (question != null && !question.isBlank()) {
            data.put("question", question);
        }
        String message = "（基于刚才识别的图片，无需重发）" + image.description();
        return ToolResult.success(data, elapsed(start)).withMessage(message);
    }

    /**
     * 缓存年龄提示（供模型判断结论新鲜度；超 1 小时标注时长，避免把旧结论说成刚看）。
     */
    private static String describeAge(Instant recognizedAt) {
        if (recognizedAt == null) {
            return null;
        }
        Duration age = Duration.between(recognizedAt, Instant.now());
        if (age.isNegative() || age.toMinutes() < 60) {
            return "刚刚识别";
        }
        return "识别于 " + (age.toHours() < 24 ? age.toHours() + " 小时前" : "超过 1 天前");
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
