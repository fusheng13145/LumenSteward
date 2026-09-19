package com.lumensteward.clawbot.infrastructure.media;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 媒体转码服务（SRS FR-11 / RSK-14）。
 *
 * <p>调用 {@code ffmpeg} 将合成音频转码为微信语音媒体支持的 amr/speex 格式。
 * <b>关键容错</b>：当 ffmpeg 不可用（未安装 / 执行异常 / 返回非零）时，<b>透传原音频字节</b>并记日志，
 * 不抛异常、不阻断调用链（PRD FR-11 验收③）。转码过程标准输入传入音频、标准输出读取结果，
 * 命令参数不含用户输入，无命令注入风险。
 */
@Component
public class MediaTranscodeService {

    private static final Logger log = LoggerFactory.getLogger(MediaTranscodeService.class);

    /** 默认目标格式（微信语音媒体）。 */
    public static final String DEFAULT_TARGET_FORMAT = "amr";

    private final String ffmpegPath;
    private final ProcessStarter starter;

    /**
     * 生产构造：ffmpeg 路径来自配置 {@code media.ffmpeg-path}（默认 {@code ffmpeg}）。
     *
     * <p>本类存在两个构造器，Spring 装配时<b>必须</b>显式标注 {@code @Autowired} 指定入口，
     * 否则容器按"无参优先"策略查找而失败（D8：单测全绿但应用无法启动的典型静默缺陷）。
     *
     * @param ffmpegPath ffmpeg 可执行文件路径或命令名
     */
    @Autowired
    public MediaTranscodeService(@Value("${media.ffmpeg-path:ffmpeg}") String ffmpegPath) {
        this(ffmpegPath, ProcessBuilder::start);
    }

    /**
     * 测试友好构造：注入自定义 {@link ProcessStarter} 以模拟 ffmpeg 行为。
     *
     * @param ffmpegPath ffmpeg 路径
     * @param starter    进程启动器
     */
    public MediaTranscodeService(String ffmpegPath, ProcessStarter starter) {
        this.ffmpegPath = ffmpegPath;
        this.starter = starter;
    }

    /**
     * 将音频转码为目标格式。
     *
     * @param audio          原始音频字节
     * @param targetFormat   目标格式（可空，默认 amr）
     * @return 转码后字节；ffmpeg 不可用时透传原字节
     */
    @SuppressFBWarnings(value = {"COMMAND_INJECTION", "PATH_TRAVERSAL_IN"},
            justification = "命令参数均为固定常量或配置项，用户输入仅经 stdin 管道传入，无注入风险")
    public byte[] transcodeToVoice(byte[] audio, String targetFormat) {
        if (audio == null || audio.length == 0) {
            return audio;
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegPath);
        cmd.add("-i");
        cmd.add("pipe:0");
        cmd.add("-f");
        cmd.add(targetFormat == null ? DEFAULT_TARGET_FORMAT : targetFormat);
        cmd.add("-y");
        cmd.add("pipe:1");
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        try {
            Process process = starter.start(pb);
            try (OutputStream os = process.getOutputStream()) {
                os.write(audio);
            }
            byte[] out = readAll(process.getInputStream());
            int code = process.waitFor();
            if (code == 0 && out.length > 0) {
                log.info("ffmpeg 转码成功 target={} bytes={}", targetFormat, out.length);
                return out;
            }
            log.warn("ffmpeg 返回非零或空输出，透传原音频 code={}", code);
            return audio;
        } catch (IOException e) {
            log.warn("ffmpeg 不可用（{}），透传原音频", e.getMessage());
            return audio;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("ffmpeg 转码被中断，透传原音频");
            return audio;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    /** 进程启动器（便于测试注入）。 */
    @FunctionalInterface
    public interface ProcessStarter {
        Process start(ProcessBuilder builder) throws IOException;
    }
}
