package com.lumensteward.clawbot.domain.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lumensteward.clawbot.application.config.ConfigKeys;
import com.lumensteward.clawbot.application.config.DynamicConfigService;
import com.lumensteward.clawbot.common.util.JsonUtils;
import com.lumensteward.clawbot.domain.intent.IntentType;
import com.lumensteward.clawbot.domain.port.MediaDispatchException;
import com.lumensteward.clawbot.domain.port.MediaDispatchPort;
import com.lumensteward.clawbot.domain.port.VoiceSynthesisException;
import com.lumensteward.clawbot.domain.port.VoiceSynthesisPort;
import com.lumensteward.clawbot.domain.port.model.MediaReference;
import com.lumensteward.clawbot.domain.tool.JsonSchema;
import com.lumensteward.clawbot.domain.tool.Tool;
import com.lumensteward.clawbot.domain.tool.ToolContext;
import com.lumensteward.clawbot.domain.tool.ToolResult;
import com.lumensteward.clawbot.infrastructure.media.MediaTranscodeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 语音生成工具（架构 5.1 / SRS FR-11，name=synthesize_voice）。
 *
 * <p>{@code idempotent=false}（下发是副作用，不可自动重放）、{@code critical=false}（失败回退文字，
 * 不中断）。流程：{@code VoiceSynthesisPort} 合成 → {@code MediaTranscodeService} ffmpeg 转码 →
 * {@code MediaDispatchPort} 经微信下发。TTS 失败时回退文字，不损坏音频、不抛异常（FR-11 验收③）。
 *
 * <p><b>W13 音色受控目录：</b>{@code voice_id} 不再是自由字符串——空值用默认音色
 * （{@code tts.voice-default}），非空值必须在白名单目录（{@code tts.voice-catalog}）内，
 * 越界返回 {@code VOICE_NOT_ALLOWED} 结构化失败并列出可用音色（FR-18 值域口径，
 * 母本 M-2「音色无受控目录」教训的收敛式吸收）。
 */
@Component
public class SynthesizeVoiceTool implements Tool {

    /** 工具名（全局唯一，FR-23）。 */
    public static final String NAME = "synthesize_voice";

    private static final Logger log = LoggerFactory.getLogger(SynthesizeVoiceTool.class);

    /** 文本上限（≤ 250 字，SRS FR-11）。 */
    private static final int MAX_TEXT_LEN = 250;

    /** 内置默认音色目录（与 V1.0.18 播种值逐字相等；配置缺失时兜底）。 */
    public static final List<String> DEFAULT_VOICE_CATALOG =
            List.of("default", "gentle", "calm", "lively", "story", "news");

    /** 内置默认音色（voice_id 空时使用）。 */
    public static final String DEFAULT_VOICE = "default";

    private static final String DESCRIPTION =
            "将文本合成为语音并发送到用户微信。适用于用户要求'读给我听'、'用语音回复'、'念一下'等场景。";

    private static final String PARAMETERS_SCHEMA = """
            {"type":"object","properties":{
              "text":{"type":"string","description":"要转换为语音的文本（≤250字）"},
              "voice_id":{"type":"string","description":"音色标识，可选；须在可用音色目录内，空则用默认音色"}
            },"required":["text"]}
            """;

    private final VoiceSynthesisPort voiceSynthesisPort;
    private final MediaDispatchPort mediaDispatchPort;
    private final MediaTranscodeService transcodeService;
    /** 动态配置源（可为 null，此时用内置默认目录；G-14）。 */
    private final DynamicConfigService dynamicConfig;
    private final JsonSchema schema;

    /**
     * Spring 装配用构造器（G-14）。
     *
     * @param voiceSynthesisPort 语音合成端口
     * @param mediaDispatchPort  媒体下发端口
     * @param transcodeService   转码服务
     * @param dynamicConfig      动态配置源（音色目录/默认音色热生效；可为 null）
     */
    @Autowired
    public SynthesizeVoiceTool(VoiceSynthesisPort voiceSynthesisPort,
                               MediaDispatchPort mediaDispatchPort,
                               MediaTranscodeService transcodeService,
                               DynamicConfigService dynamicConfig) {
        this.voiceSynthesisPort = voiceSynthesisPort;
        this.mediaDispatchPort = mediaDispatchPort;
        this.transcodeService = transcodeService;
        this.dynamicConfig = dynamicConfig;
        this.schema = JsonSchema.of(PARAMETERS_SCHEMA);
    }

    /**
     * 兼容构造（无动态配置源）：保留给既有单测，行为等同内置默认目录。
     */
    public SynthesizeVoiceTool(VoiceSynthesisPort voiceSynthesisPort,
                               MediaDispatchPort mediaDispatchPort,
                               MediaTranscodeService transcodeService) {
        this(voiceSynthesisPort, mediaDispatchPort, transcodeService, null);
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
    public Set<String> claimKeywords() {
        return Set.of("发送", "合成", "语音");
    }

    @Override
    public String monitorDomain() {
        return "tts";
    }

    @Override
    public IntentType taskIntent() {
        return IntentType.TTS;
    }

    @Override
    public ToolResult execute(ToolContext context, JsonNode args) {
        long start = System.currentTimeMillis();
        String openid = context == null ? null : context.openid();
        String text = text(args, "text");
        if (text == null || text.isBlank()) {
            return ToolResult.failure("INVALID_ARGS", "缺少要合成语音的文本 text", false);
        }
        if (text.length() > MAX_TEXT_LEN) {
            return ToolResult.failure("INVALID_ARGS", "文本超过 " + MAX_TEXT_LEN + " 字上限", false);
        }
        String voiceId = text(args, "voice_id");
        // W13 音色受控目录：空值用默认音色，非空必须在白名单内（越界拒绝，FR-18 值域口径）
        String resolvedVoice = resolveVoice(voiceId);
        if (resolvedVoice == null) {
            return ToolResult.failure("VOICE_NOT_ALLOWED",
                    "音色「" + voiceId.trim() + "」不在可用目录中，可用音色: "
                            + String.join("、", voiceCatalog()) + "，请改用其中一个或留空", false);
        }
        // W12：合成前清洗括号旁注/语气词（母本 M-2 教训：旁注被一字不落读出）；
        // 文字回退路径仍用原文，保证降级信息不丢
        String speakText = VoiceTextCleaner.clean(text);
        try {
            byte[] audio = voiceSynthesisPort.synthesize(speakText, resolvedVoice);
            if (audio == null || audio.length == 0) {
                // TTS 失败：回退文字，不损坏音频、不抛异常（FR-11 验收③）
                return ToolResult.failure("TTS_FAILED", "语音暂时无法生成，以下是文字：" + text, false);
            }
            byte[] transcoded = transcodeService.transcodeToVoice(audio, "amr");
            MediaReference ref = mediaDispatchPort.sendVoice(openid, transcoded, "clawbot-voice.amr");
            ObjectNode data = JsonUtils.mapper().createObjectNode();
            data.put("media_id", ref.mediaId());
            data.put("media_type", ref.mediaType());
            // W12/W13：清洗后的合成文本随结果留痕（log_tool_call/回放台可核），运行期样本可见
            data.put("speak_text", speakText);
            data.put("voice_id", resolvedVoice);
            return ToolResult.success(data, elapsed(start))
                    .withMessage("已为你生成语音，请查收");
        } catch (VoiceSynthesisException | MediaDispatchException e) {
            log.warn("语音生成或下发异常，回退文字: {}", e.getMessage());
            return ToolResult.failure("TTS_FAILED", "语音暂时无法生成，以下是文字：" + text, false);
        } catch (RuntimeException e) {
            log.warn("语音工具执行异常，回退文字: {}", e.getMessage());
            return ToolResult.failure("TTS_FAILED", "语音暂时无法生成，以下是文字：" + text, false);
        }
    }

    /**
     * 解析音色：空值回落默认音色；命中目录返回原值；越界返回 null（由调用方转结构化失败）。
     */
    private String resolveVoice(String voiceId) {
        List<String> catalog = voiceCatalog();
        if (voiceId == null || voiceId.isBlank()) {
            String fallback = dynamicConfig == null
                    ? DEFAULT_VOICE
                    : dynamicConfig.getString(ConfigKeys.TTS_VOICE_DEFAULT, DEFAULT_VOICE);
            return catalog.contains(fallback) ? fallback : DEFAULT_VOICE;
        }
        return catalog.contains(voiceId.trim()) ? voiceId.trim() : null;
    }

    /** 音色目录：sys_config 热读（FR-18），缺失/非法回退内置默认（与播种值逐字相等）。 */
    private List<String> voiceCatalog() {
        if (dynamicConfig == null) {
            return DEFAULT_VOICE_CATALOG;
        }
        List<String> catalog = dynamicConfig.getList(ConfigKeys.TTS_VOICE_CATALOG, DEFAULT_VOICE_CATALOG);
        return catalog == null || catalog.isEmpty() ? DEFAULT_VOICE_CATALOG : catalog;
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
