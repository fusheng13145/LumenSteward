package com.lumensteward.clawbot.infrastructure.bootstrap;

import com.lumensteward.clawbot.infrastructure.bootstrap.doctor.DependencyCheck;
import com.lumensteward.clawbot.infrastructure.bootstrap.doctor.DoctorReport;
import com.lumensteward.clawbot.infrastructure.config.properties.LlmProperties;
import com.lumensteward.clawbot.infrastructure.config.properties.WechatProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@link StartupDoctor} 默认实现（SUP-05 / AC-D4 / 架构 5.4）。
 *
 * <p>设计要点：
 * <ul>
 *   <li>单项探测超时 {@value #PROBE_TIMEOUT_SECONDS}s，超时即判 {@code TIMEOUT}，不无限等待；</li>
 *   <li>整份体检<b>绝不抛出异常</b>——任何失败都以 {@link DependencyCheck} 的形式呈现，
 *       使 {@code GET /api/doctor} 永不为 5xx（AC-D4）。</li>
 *   <li>MOCK 模式（微信/LLM）视为「自洽可用」，不计入降级；真实外部依赖仅做<b>配置就绪性</b>校验
 *       （不主动实拨，避免启动期对外部服务产生副作用，实拨在 T03 的 SPI 实现中按需进行）。</li>
 * </ul>
 */
@Component
public class StartupDoctorImpl implements StartupDoctor {

    private static final Logger log = LoggerFactory.getLogger(StartupDoctorImpl.class);

    /** 单项探测超时（秒）。 */
    private static final long PROBE_TIMEOUT_SECONDS = 2L;

    /** 单项探测超时（Duration）。 */
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(PROBE_TIMEOUT_SECONDS);

    /** MySQL 体检项名（用于整体结论判定）。 */
    private static final String NAME_DATABASE = "MySQL";

    private static final String KEY_TTS = "tts.key";
    private static final String KEY_LOGISTICS = "logistics.key";
    private static final String KEY_MAP = "map.key";

    private final DataSource dataSource;
    private final RedisConnectionFactory redisConnectionFactory;
    private final WechatProperties wechatProperties;
    private final LlmProperties llmProperties;
    private final Environment environment;

    /**
     * 构造器注入（G-14）。
     *
     * @param dataSource            数据源
     * @param redisConnectionFactory Redis 连接工厂
     * @param wechatProperties      微信通道配置
     * @param llmProperties         LLM 配置
     * @param environment           运行环境（读取 TTS/物流/地图 等可选依赖的密钥项是否存在）
     */
    public StartupDoctorImpl(DataSource dataSource,
                             RedisConnectionFactory redisConnectionFactory,
                             WechatProperties wechatProperties,
                             LlmProperties llmProperties,
                             Environment environment) {
        this.dataSource = dataSource;
        this.redisConnectionFactory = redisConnectionFactory;
        this.wechatProperties = wechatProperties;
        this.llmProperties = llmProperties;
        this.environment = environment;
    }

    @Override
    public DoctorReport diagnose() {
        List<DependencyCheck> items = new ArrayList<>();
        try {
            items.add(checkDatabase());
            items.add(checkRedis());
            items.add(checkWechat());
            items.add(checkLlm());
            items.add(checkConfiguredDependency("TTS", KEY_TTS));
            items.add(checkConfiguredDependency("物流", KEY_LOGISTICS));
            items.add(checkConfiguredDependency("地图", KEY_MAP));
            return new DoctorReport(overallOf(items), Instant.now(), items);
        } catch (RuntimeException ex) {
            // 兜底：体检过程本身异常也不得导致 5xx
            log.error("Startup Doctor 执行异常，返回降级报告", ex);
            DependencyCheck fallback = new DependencyCheck(
                    "体检", DependencyCheck.DOWN, "unknown", "内部异常", "体检过程异常，请查看日志");
            return new DoctorReport(DoctorReport.OVERALL_DOWN, Instant.now(), List.of(fallback));
        }
    }

    private DependencyCheck checkDatabase() {
        String connectivity = probe(() -> {
            try (Connection connection = dataSource.getConnection()) {
                return connection.isValid((int) PROBE_TIMEOUT_SECONDS);
            }
        });
        String conclusion = DependencyCheck.UP.equals(connectivity) ? "就绪" : "不可达";
        return new DependencyCheck(NAME_DATABASE, connectivity, "mysql", conclusion, "连接有效性探测");
    }

    private DependencyCheck checkRedis() {
        String connectivity = probe(() -> {
            try (RedisConnection connection = redisConnectionFactory.getConnection()) {
                String pong = connection.ping();
                return pong != null && !pong.isBlank();
            }
        });
        String conclusion = DependencyCheck.UP.equals(connectivity) ? "就绪" : "不可达";
        return new DependencyCheck("Redis", connectivity, "redis", conclusion, "PING 探测");
    }

    private DependencyCheck checkWechat() {
        if (wechatProperties.mockEnabled()) {
            return new DependencyCheck("微信通道", DependencyCheck.UP, "MOCK",
                    "Mock 模式自洽可用", "wx.mock-enabled=true");
        }
        boolean configured = !isBlank(wechatProperties.token()) && !isBlank(wechatProperties.appId());
        String connectivity = configured ? DependencyCheck.UP : DependencyCheck.DOWN;
        String conclusion = configured ? "配置就绪(REAL)" : "缺少 wx.token / wx.app-id";
        return new DependencyCheck("微信通道", connectivity, "REAL", conclusion, "wx.mock-enabled=false");
    }

    private DependencyCheck checkLlm() {
        if ("mock".equalsIgnoreCase(llmProperties.provider())) {
            return new DependencyCheck("LLM", DependencyCheck.UP, "mock",
                    "Mock 模式自洽可用", "llm.provider=mock");
        }
        boolean configured = !isBlank(llmProperties.baseUrl())
                && !isBlank(llmProperties.apiKey())
                && !isBlank(llmProperties.model());
        String connectivity = configured ? DependencyCheck.UP : DependencyCheck.DOWN;
        String conclusion = configured ? "配置就绪(real)" : "缺少 llm.base-url / llm.api-key / llm.model";
        return new DependencyCheck("LLM", connectivity, "real", conclusion,
                "llm.provider=" + llmProperties.provider());
    }

    /**
     * 校验「可选外部依赖」的配置就绪性（TTS / 物流 / 地图）。
     *
     * @param name        依赖名
     * @param keyProperty 密钥类配置键
     * @return 体检项（未配置为 {@code N/A}，不计入降级）
     */
    private DependencyCheck checkConfiguredDependency(String name, String keyProperty) {
        String value = environment.getProperty(keyProperty);
        boolean configured = !isBlank(value);
        String mode = configured ? "real" : DependencyCheck.MODE_NOT_CONFIGURED;
        String connectivity = configured ? DependencyCheck.UP : DependencyCheck.NOT_CONFIGURED;
        String conclusion = configured ? "配置就绪" : "未配置(可选依赖)";
        return new DependencyCheck(name, connectivity, mode, conclusion, "key=" + keyProperty);
    }

    /**
     * 依据各项连通性汇总整体结论。
     *
     * @param items 体检项
     * @return 整体结论
     */
    private String overallOf(List<DependencyCheck> items) {
        Optional<DependencyCheck> database = items.stream()
                .filter(item -> NAME_DATABASE.equals(item.name()))
                .findFirst();
        if (database.isPresent() && !DependencyCheck.UP.equals(database.get().connectivity())) {
            return DoctorReport.OVERALL_DOWN;
        }
        boolean degraded = items.stream().anyMatch(item ->
                DependencyCheck.DOWN.equals(item.connectivity())
                        || DependencyCheck.TIMEOUT.equals(item.connectivity()));
        return degraded ? DoctorReport.OVERALL_DEGRADED : DoctorReport.OVERALL_UP;
    }

    /**
     * 在独立线程中执行探测，并以 {@value #PROBE_TIMEOUT_SECONDS}s 为上限。
     *
     * @param task 返回 true 表示连通
     * @return {@link DependencyCheck#UP} / {@link DependencyCheck#DOWN} / {@link DependencyCheck#TIMEOUT}
     */
    private String probe(Callable<Boolean> task) {
        ExecutorService executor = Executors.newSingleThreadExecutor(StartupDoctorImpl::newProbeThread);
        Future<Boolean> future = executor.submit(task);
        try {
            Boolean result = future.get(PROBE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            return Boolean.TRUE.equals(result) ? DependencyCheck.UP : DependencyCheck.DOWN;
        } catch (TimeoutException ex) {
            future.cancel(true);
            return DependencyCheck.TIMEOUT;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return DependencyCheck.DOWN;
        } catch (ExecutionException ex) {
            return DependencyCheck.DOWN;
        } finally {
            executor.shutdownNow();
        }
    }

    private static Thread newProbeThread(Runnable runnable) {
        Thread thread = new Thread(runnable, "startup-doctor-probe");
        thread.setDaemon(true);
        return thread;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
