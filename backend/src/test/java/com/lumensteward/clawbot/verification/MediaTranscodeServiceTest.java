package com.lumensteward.clawbot.verification;

import com.lumensteward.clawbot.infrastructure.media.MediaTranscodeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link MediaTranscodeService} 单元测试（FR-11 / RSK-14）。
 *
 * <p>关键容错：ffmpeg 不可用 / 非零退出 / 被中断时，<b>透传原音频字节</b>，不抛异常、不阻断调用链。
 */
class MediaTranscodeServiceTest {

    private static final byte[] AUDIO = "original-audio-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    private static final byte[] TRANSCODED = "amr-output".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    /** 构造一个返回指定退出码与输出的假进程启动器。 */
    private static MediaTranscodeService.ProcessStarter makeProcess(int exitCode, byte[] out) throws IOException, InterruptedException {
        java.lang.Process p = mock(java.lang.Process.class);
        when(p.getOutputStream()).thenReturn(new ByteArrayOutputStream());
        when(p.getInputStream()).thenReturn(new ByteArrayInputStream(out));
        when(p.getErrorStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(p.waitFor()).thenReturn(exitCode);
        return pb -> p;
    }

    @Test
    @DisplayName("RSK-14：ffmpeg 成功（exit 0）→ 返回转码结果")
    void shouldReturnTranscodedOnSuccess() throws Exception {
        MediaTranscodeService svc = new MediaTranscodeService("ffmpeg", makeProcess(0, TRANSCODED));

        byte[] result = svc.transcodeToVoice(AUDIO, "amr");

        assertThat(result).isEqualTo(TRANSCODED);
    }

    @Test
    @DisplayName("RSK-14：ffmpeg 非零退出 → 透传原音频")
    void shouldPassthroughOnNonZeroExit() throws Exception {
        MediaTranscodeService svc = new MediaTranscodeService("ffmpeg", makeProcess(1, new byte[0]));

        byte[] result = svc.transcodeToVoice(AUDIO, "amr");

        assertThat(result).isEqualTo(AUDIO);
    }

    @Test
    @DisplayName("RSK-14：ffmpeg 不可用（IOException）→ 透传原音频")
    void shouldPassthroughOnIoException() {
        MediaTranscodeService.ProcessStarter failing = pb -> {
            throw new IOException("ffmpeg not found");
        };
        MediaTranscodeService svc = new MediaTranscodeService("ffmpeg", failing);

        byte[] result = svc.transcodeToVoice(AUDIO, "amr");

        assertThat(result).isEqualTo(AUDIO);
    }

    @Test
    @DisplayName("RSK-14：空输入 → 原样返回")
    void shouldReturnEmptyForEmptyInput() throws Exception {
        MediaTranscodeService svc = new MediaTranscodeService("ffmpeg", makeProcess(0, TRANSCODED));

        byte[] result = svc.transcodeToVoice(new byte[0], "amr");

        assertThat(result).isEmpty();
    }
}
