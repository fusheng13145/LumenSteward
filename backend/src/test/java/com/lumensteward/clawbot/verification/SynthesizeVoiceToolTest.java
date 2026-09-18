package com.lumensteward.clawbot.verification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

    private static JsonNode text(String t) {
        ObjectNode node = JsonUtils.mapper().createObjectNode();
        if (t != null) {
            node.put("text", t);
        }
        return node;
    }
}
