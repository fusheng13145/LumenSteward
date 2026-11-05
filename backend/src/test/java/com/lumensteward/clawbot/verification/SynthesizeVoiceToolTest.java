package com.lumensteward.clawbot.verification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lumensteward.clawbot.application.config.DynamicConfigService;
import com.lumensteward.clawbot.common.util.JsonUtils;
import com.lumensteward.clawbot.domain.port.MediaDispatchException;
import com.lumensteward.clawbot.domain.port.MediaDispatchPort;
import com.lumensteward.clawbot.domain.port.VoiceSynthesisException;
import com.lumensteward.clawbot.domain.port.VoiceSynthesisPort;
import com.lumensteward.clawbot.domain.port.model.MediaReference;
import com.lumensteward.clawbot.domain.tool.ToolContext;
import com.lumensteward.clawbot.domain.tool.ToolResult;
import com.lumensteward.clawbot.domain.tool.impl.SynthesizeVoiceTool;
import com.lumensteward.clawbot.infrastructure.media.MediaTranscodeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SynthesizeVoiceTool} 单元测试（FR-11 / SRS）。
 *
 * <p>覆盖：成功合成并下发、TTS 失败回退文字不抛异常、参数缺失、超长文本、下发异常回退。
 */
class SynthesizeVoiceToolTest {

    private static final ToolContext CTX = ToolContext.of("trace-v", "openid-v", 1L, 1);

    private static final VoiceSynthesisPort OK_SYNTH = (text, voiceId) ->
            ("mock-audio:" + text).getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private final MediaTranscodeService transcode = new MediaTranscodeService("ffmpeg");
    private final MediaDispatchPort okDispatch = (openid, bytes, filename) ->
            new MediaReference("media-123", "voice");

    @Test
    @DisplayName("FR-11：成功合成语音并下发 → 返回 media_id")
    void shouldSynthesizeAndDispatch() {
        SynthesizeVoiceTool tool = new SynthesizeVoiceTool(OK_SYNTH, okDispatch, transcode);

        ToolResult result = tool.execute(CTX, text("念一下这段文字"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.data().get("media_id").asText()).isEqualTo("media-123");
        assertThat(result.message()).contains("已为你生成语音");
    }

    @Test
    @DisplayName("FR-11：TTS 返回空 → 回退文字，不抛异常（FR-11 验收③）")
    void shouldFallbackWhenTtsReturnsEmpty() {
        VoiceSynthesisPort empty = (text, voiceId) -> new byte[0];
        SynthesizeVoiceTool tool = new SynthesizeVoiceTool(empty, okDispatch, transcode);

        ToolResult result = tool.execute(CTX, text("念一下"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errorType()).isEqualTo("TTS_FAILED");
        assertThat(result.message()).contains("语音暂时无法生成");
    }

    @Test
    @DisplayName("FR-11：缺少文本 → INVALID_ARGS")
    void shouldRejectMissingText() {
        SynthesizeVoiceTool tool = new SynthesizeVoiceTool(OK_SYNTH, okDispatch, transcode);

        ToolResult result = tool.execute(CTX, text(null));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errorType()).isEqualTo("INVALID_ARGS");
    }

    @Test
    @DisplayName("FR-11：文本超 250 字上限 → INVALID_ARGS")
    void shouldRejectTooLongText() {
        SynthesizeVoiceTool tool = new SynthesizeVoiceTool(OK_SYNTH, okDispatch, transcode);

        ToolResult result = tool.execute(CTX, text("字".repeat(251)));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errorType()).isEqualTo("INVALID_ARGS");
    }

    @Test
    @DisplayName("FR-11：下发异常 → 回退文字不中断")
    void shouldFallbackWhenDispatchFails() {
        MediaDispatchPort failing = (openid, bytes, filename) -> {
            throw new MediaDispatchException("下发失败");
        };
        SynthesizeVoiceTool tool = new SynthesizeVoiceTool(OK_SYNTH, failing, transcode);

        ToolResult result = tool.execute(CTX, text("念一下"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errorType()).isEqualTo("TTS_FAILED");
    }

    @Test
    @DisplayName("FR-11：合成抛异常 → 回退文字")
    void shouldFallbackWhenSynthesisThrows() {
        VoiceSynthesisPort throwing = (text, voiceId) -> {
            throw new VoiceSynthesisException("合成器故障");
        };
        SynthesizeVoiceTool tool = new SynthesizeVoiceTool(throwing, okDispatch, transcode);

        ToolResult result = tool.execute(CTX, text("念一下"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errorType()).isEqualTo("TTS_FAILED");
    }

    // ===== W13 音色受控目录 =====

    @Test
    @DisplayName("W13：voice_id 在内置目录内 → 放行并随结果留痕（speak_text/voice_id）")
    void shouldAllowCatalogVoiceAndRecordSpeakText() {
        SynthesizeVoiceTool tool = new SynthesizeVoiceTool(OK_SYNTH, okDispatch, transcode);

        ToolResult result = tool.execute(CTX, voiceArgs("念一下", "gentle"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.data().path("voice_id").asText()).isEqualTo("gentle");
        // W12 运行期样本：合成文本为清洗后口径（旁注剥除）
        assertThat(result.data().path("speak_text").asText()).isEqualTo("念一下");
    }

    @Test
    @DisplayName("W13：voice_id 越界 → VOICE_NOT_ALLOWED 并列出可用音色，不触达合成")
    void shouldRejectVoiceOutsideCatalog() {
        VoiceSynthesisPort never = (text, voiceId) -> {
            throw new AssertionError("越界音色不应触达合成");
        };
        SynthesizeVoiceTool tool = new SynthesizeVoiceTool(never, okDispatch, transcode);

        ToolResult result = tool.execute(CTX, voiceArgs("念一下", "robot-9000"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errorType()).isEqualTo("VOICE_NOT_ALLOWED");
        assertThat(result.message()).contains("default").contains("gentle");
    }

    @Test
    @DisplayName("W13：voice_id 空白 → 用默认音色；配置目录热生效（自定义目录放行/拒绝）")
    void shouldUseDefaultAndHonorConfiguredCatalog() {
        DynamicConfigService config = config(Map.of(
                "tts.voice-catalog", "[\"xiaochen\",\"xiaolan\"]",
                "tts.voice-default", "xiaochen"));
        SynthesizeVoiceTool tool = new SynthesizeVoiceTool(OK_SYNTH, okDispatch, transcode, config);

        assertThat(tool.execute(CTX, text("念一下")).isSuccess()).isTrue();
        assertThat(tool.execute(CTX, voiceArgs("念一下", "xiaolan")).isSuccess()).isTrue();
        ToolResult rejected = tool.execute(CTX, voiceArgs("念一下", "default"));
        assertThat(rejected.errorType()).isEqualTo("VOICE_NOT_ALLOWED");
    }

    @Test
    @DisplayName("W13：配置目录非法（空数组）→ 回退内置默认目录，不放行任意值")
    void shouldFallbackToBuiltinCatalogOnInvalidConfig() {
        DynamicConfigService broken = config(Map.of("tts.voice-catalog", "[]"));
        SynthesizeVoiceTool tool = new SynthesizeVoiceTool(OK_SYNTH, okDispatch, transcode, broken);

        assertThat(tool.execute(CTX, voiceArgs("念一下", "default")).isSuccess()).isTrue();
        assertThat(tool.execute(CTX, voiceArgs("念一下", "whatever")).errorType())
                .isEqualTo("VOICE_NOT_ALLOWED");
    }

    private static JsonNode voiceArgs(String t, String voiceId) {
        ObjectNode node = JsonUtils.mapper().createObjectNode();
        node.put("text", t);
        node.put("voice_id", voiceId);
        return node;
    }

    /** 键值表驱动的 DynamicConfigService 桩（仅覆盖音色两键所需方法）。 */
    private static DynamicConfigService config(Map<String, String> values) {
        return new DynamicConfigService() {
            @Override
            public String get(String key) {
                return values.get(key);
            }

            @Override
            public String getString(String key, String fallback) {
                String v = values.get(key);
                return v == null || v.isBlank() ? fallback : v;
            }

            @Override
            public int getInt(String key, int fallback) {
                return fallback;
            }

            @Override
            public long getLong(String key, long fallback) {
                return fallback;
            }

            @Override
            public boolean getBoolean(String key, boolean fallback) {
                return fallback;
            }

            @Override
            public List<String> getList(String key, List<String> fallback) {
                String v = values.get(key);
                if (v == null || v.isBlank()) {
                    return fallback;
                }
                try {
                    com.fasterxml.jackson.databind.JsonNode node = JsonUtils.mapper().readTree(v);
                    List<String> out = new java.util.ArrayList<>();
                    if (node != null && node.isArray()) {
                        node.forEach(item -> out.add(item.asText()));
                    }
                    return out;
                } catch (Exception e) {
                    return fallback;
                }
            }

            @Override
            public Set<String> getSet(String key, Set<String> fallback) {
                return fallback;
            }

            @Override
            public boolean isAvailable() {
                return true;
            }
        };
    }

    private static JsonNode text(String t) {
        ObjectNode node = JsonUtils.mapper().createObjectNode();
        if (t != null) {
            node.put("text", t);
        }
        return node;
    }
}
