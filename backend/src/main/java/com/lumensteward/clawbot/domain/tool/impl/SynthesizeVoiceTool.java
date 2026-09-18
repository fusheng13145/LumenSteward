package com.lumensteward.clawbot.domain.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lumensteward.clawbot.common.util.JsonUtils;
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
import org.springframework.stereotype.Component;

/**
 * 语音生成工具（架构 5.1 / SRS FR-11，name=synthesize_voice）。
 *
 * <p>{@code idempotent=false}（下发是副作用，不可自动重放）、{@code critical=false}（失败回退文字，
 * 不中断）。流程：{@code VoiceSynthesisPort} 合成 → {@code MediaTranscodeService} ffmpeg 转码 →
 * {@code MediaDispatchPort} 经微信下发。TTS 失败时回退文字，不损坏音频、不抛异常（FR-11 验收③）。
 */
@Component
public class SynthesizeVoiceTool implements Tool {

    /** 工具名（全局唯一，FR-23）。 */
    public static final String NAME = "synthesize_voice";

    private static final Logger log = LoggerFactory.getLogger(SynthesizeVoiceTool.class);

    /** 文本上限（≤ 250 字，SRS FR-11）。 */
    private static final int MAX_TEXT_LEN = 250;

    private static final String DESCRIPTION =
            "将文本合成为语音并发送到用户微信。适用于用户要求'读给我听'、'用语音回复'、'念一下'等场景。";

    private static final String PARAMETERS_SCHEMA = """
            {"type":"object","properties":{
              "text":{"type":"string","description":"要转换为语音的文本（≤250字）"},
              "voice_id":{"type":"string","description":"音色标识，可空使用默认音色"}
            },"required":["text"]}
            """;

    private final VoiceSynthesisPort voiceSynthesisPort;
    private final MediaDispatchPort mediaDispatchPort;
    private final MediaTranscodeService transcodeService;
    private final JsonSchema schema;

    /**
     * 构造器注入（G-14）。
     *
     * @param voiceSynthesisPort 语音合成端口
     * @param mediaDispatchPort  媒体下发端口
     * @param transcodeService   转码服务
     */
    public SynthesizeVoiceTool(VoiceSynthesisPort voiceSynthesisPort,
                               MediaDispatchPort mediaDispatchPort,
                               MediaTranscodeService transcodeService) {
        this.voiceSynthesisPort = voiceSynthesisPort;
        this.mediaDispatchPort = mediaDispatchPort;
        this.transcodeService = transcodeService;
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
        try {
            byte[] audio = voiceSynthesisPort.synthesize(text, voiceId);
            if (audio == null || audio.length == 0) {
                // TTS 失败：回退文字，不损坏音频、不抛异常（FR-11 验收③）
                return ToolResult.failure("TTS_FAILED", "语音暂时无法生成，以下是文字：" + text, false);
            }
            byte[] transcoded = transcodeService.transcodeToVoice(audio, "amr");
            MediaReference ref = mediaDispatchPort.sendVoice(openid, transcoded, "clawbot-voice.amr");
            ObjectNode data = JsonUtils.mapper().createObjectNode();
            data.put("media_id", ref.mediaId());
            data.put("media_type", ref.mediaType());
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
