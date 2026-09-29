package com.lumensteward.clawbot.domain.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lumensteward.clawbot.common.util.JsonUtils;
import com.lumensteward.clawbot.domain.context.RecentImageStore;
import com.lumensteward.clawbot.domain.intent.IntentType;
import com.lumensteward.clawbot.domain.model.PetProfileView;
import com.lumensteward.clawbot.domain.model.RecentImage;
import com.lumensteward.clawbot.domain.port.VisionPort;
import com.lumensteward.clawbot.domain.port.VisionPortException;
import com.lumensteward.clawbot.domain.port.model.VisionResult;
import com.lumensteward.clawbot.domain.service.PetProfileService;
import com.lumensteward.clawbot.domain.tool.JsonSchema;
import com.lumensteward.clawbot.domain.tool.Tool;
import com.lumensteward.clawbot.domain.tool.ToolContext;
import com.lumensteward.clawbot.domain.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;

/**
 * 图片识别工具（架构 5.1 / SRS FR-10 / FR-06，name=recognize_image）。
 *
 * <p>{@code idempotent=true}（只读识别）、{@code critical=false}（识别失败降级而非中断）。
 * 支持 pet/object/ocr 三类场景；置信度不足一律以"可能/疑似"措辞（AC-FR-10③）；
 * 模糊图（极低置信度）返回 {@code EMPTY_RESULT} 不虚构（BR-09）；pet 场景可经
 * {@link PetProfileService} 由品种/名称反查档案称谓（FR-14 贯通）。
 *
 * <p><b>W11 识图缓存：</b>识别成功（非模糊）后把结论按 openid 写入 {@link RecentImageStore}
 * （覆盖写 = 换图即换缓存），供 {@link AskImageFollowupTool} 在后续追问中免重发图片续接
 * （D1 场景 S2）。写入失败只影响追问能力，不影响本轮识别回复（fail-open）。
 */
@Component
public class RecognizeImageTool implements Tool {

    /** 工具名（全局唯一，FR-23）。 */
    public static final String NAME = "recognize_image";

    private static final Logger log = LoggerFactory.getLogger(RecognizeImageTool.class);

    /** 模糊阈值：置信度低于此值视为无法识别（不虚构）。 */
    private static final double BLURRY_CONFIDENCE = 0.25;

    /** 低置信阈值：低于此值以"可能/疑似"措辞。 */
    private static final double LOW_CONFIDENCE = 0.60;

    private static final String DESCRIPTION =
            "识别用户发来的图片内容。适用于宠物识别（'这是我的猫'）、物体识别（'这是什么'）、"
                    + "OCR 文字提取（'读一下这张图里的字'）等场景。需提供 image_url 或 image_base64 之一。";

    private static final String PARAMETERS_SCHEMA = """
            {"type":"object","properties":{
              "image_url":{"type":"string","description":"图片 URL"},
              "image_base64":{"type":"string","description":"图片 Base64 编码"},
              "scene":{"type":"string","enum":["pet","object","ocr"],"description":"识别场景，默认 object"},
              "question":{"type":"string","description":"针对图片的追问，如'什么品种'"}
            },"required":[]}
            """;

    private final VisionPort visionPort;
    private final ObjectProvider<PetProfileService> petProfileProvider;
    private final RecentImageStore recentImageStore;
    private final JsonSchema schema;

    /**
     * 构造器注入（G-14）。
     *
     * @param visionPort          视觉端口
     * @param petProfileProvider 宠物档案服务（可选，容器无则跳过档案匹配）
     * @param recentImageStore   最近识图缓存（W11；为 null 时只跳过缓存写入，识别照常）
     */
    public RecognizeImageTool(VisionPort visionPort,
                              ObjectProvider<PetProfileService> petProfileProvider,
                              RecentImageStore recentImageStore) {
        this.visionPort = visionPort;
        this.petProfileProvider = petProfileProvider;
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
    public Set<String> claimKeywords() {
        return Set.of("识别", "图片");
    }

    @Override
    public String monitorDomain() {
        return "image_recognition";
    }

    @Override
    public IntentType taskIntent() {
        return IntentType.IMAGE;
    }

    @Override
    public boolean readOnly() {
        return true;
    }

    @Override
    public ToolResult execute(ToolContext context, JsonNode args) {
        long start = System.currentTimeMillis();
        String imageUrl = text(args, "image_url");
        String imageBase64 = text(args, "image_base64");
        if ((imageUrl == null || imageUrl.isBlank()) && (imageBase64 == null || imageBase64.isBlank())) {
            return ToolResult.failure("INVALID_ARGS", "缺少图片内容（image_url 或 image_base64）", false);
        }
        String scene = text(args, "scene");
        if (scene == null || scene.isBlank()) {
            scene = "object";
        }
        String question = text(args, "question");
        String imageContent = imageUrl != null && !imageUrl.isBlank() ? imageUrl : imageBase64;

        try {
            VisionResult result = visionPort.vision(
                    new com.lumensteward.clawbot.domain.port.model.VisionRequest(null, imageContent, question));
            if (result.confidence() < BLURRY_CONFIDENCE) {
                // 模糊/无法识别：返回空结果，绝不虚构（BR-09）
                return ToolResult.failure("EMPTY_RESULT",
                        "图片不够清晰，暂时无法识别，请发送更清晰的图片", false);
            }
            String description = result.description();
            boolean lowConfidence = result.confidence() < LOW_CONFIDENCE;
            if (lowConfidence && description != null) {
                description = "（可能/疑似）" + description;
            }
            String matchedProfile = null;
            if ("pet".equalsIgnoreCase(scene)) {
                String openid = context == null ? null : context.openid();
                matchedProfile = matchProfile(openid, description);
            }
            ObjectNode data = JsonUtils.mapper().createObjectNode();
            data.put("scene", scene);
            data.put("description", description);
            data.put("confidence", result.confidence());
            data.put("low_confidence", lowConfidence);
            data.put("matched_profile", matchedProfile);
            cacheForFollowup(context, description, scene, result.confidence());
            String message = "识别结果：" + description
                    + (matchedProfile != null ? "（已为你匹配到档案：" + matchedProfile + "）" : "");
            return ToolResult.success(data, elapsed(start)).withMessage(message);
        } catch (VisionPortException e) {
            log.warn("图片识别失败: {}", e.getMessage());
            return ToolResult.failure("VISION_FAILED", "图片识别失败，请稍后重试", false);
        }
    }

    /**
     * 识别成功后写识图缓存（W11）：覆盖写 = 换图即换缓存；失败仅 WARN（追问能力降级，链路不受影响）。
     *
     * @param context     执行上下文（取 openid）
     * @param description 识别描述（与回注模型口径一致）
     * @param scene       识别场景
     * @param confidence  置信度
     */
    private void cacheForFollowup(ToolContext context, String description, String scene, double confidence) {
        String openid = context == null ? null : context.openid();
        if (recentImageStore == null || openid == null || openid.isBlank() || description == null) {
            return;
        }
        boolean saved = recentImageStore.save(openid,
                new RecentImage(description, scene, confidence, Instant.now()));
        if (!saved) {
            log.warn("识图缓存写入未成功（追问工具本轮将不可见）openid 脱敏={}",
                    com.lumensteward.clawbot.common.util.MaskUtils.openid(openid));
        }
    }

    /**
     * 由识别描述反查宠物档案（FR-14 贯通）：遍历存活档案，命中品种或昵称即返回昵称。
     *
     * @param openid      用户
     * @param description 识别描述
     * @return 命中的档案昵称；未命中返回 null
     */
    private String matchProfile(String openid, String description) {
        if (openid == null || description == null || petProfileProvider == null
                || petProfileProvider.getIfAvailable() == null) {
            return null;
        }
        PetProfileService service = petProfileProvider.getIfAvailable();
        try {
            for (PetProfileView view : service.listLive(openid)) {
                if (view == null) {
                    continue;
                }
                if (view.breed() != null && !view.breed().isBlank() && description.contains(view.breed())) {
                    return view.petName();
                }
                if (view.petName() != null && !view.petName().isBlank() && description.contains(view.petName())) {
                    return view.petName();
                }
            }
        } catch (RuntimeException e) {
            log.warn("档案匹配失败（忽略）: {}", e.getMessage());
        }
        return null;
    }

    private static long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }

    private static String text(JsonNode args, String field) {
        if (args == null || !args.hasNonNull(field)) {
            return null;
        }
        return args.get(field).asText();
    }
}
