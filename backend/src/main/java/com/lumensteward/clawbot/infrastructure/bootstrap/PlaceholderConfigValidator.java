package com.lumensteward.clawbot.infrastructure.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 关键配置占位符 / 缺失校验（SUP-06 / NFR-SE-13 / AC-D2 / AC-F3）。
 *
 * <p>在启动最早阶段运行（{@link Ordered#HIGHEST_PRECEDENCE}），逐项检查密钥类关键配置：
 * <ul>
 *   <li>{@code prod} profile（或显式 {@code clawbot.startup.placeholder-fail-fast=true}）下，
 *       若存在缺失或占位符值，<b>Fail-Fast</b> 抛出异常并<b>列明缺失项</b>，拒绝启动；</li>
 *   <li>{@code local}/{@code test} 下仅输出显著 WARN，不阻断启动。</li>
 * </ul>
 *
 * <p>动机（SRS 12.6.2 G3 / A2）：前身配置中留存 {@code your_corp_secret}、{@code root/root}
 * 等占位值却仍能启动。本校验器把「配置占位符不可带上线」变成可执行的启动期门禁。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PlaceholderConfigValidator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PlaceholderConfigValidator.class);

    /** 必须校验的关键配置键（密钥类，BR-20 / NFR-SE-03）。 */
    static final List<String> CRITICAL_KEYS = List.of(
            "wx.token",
            "wx.app-id",
            "wx.app-secret",
            "wx.encoding-aes-key",
            "llm.api-key",
            "security.jwt-secret",
            "tts.key",
            "map.key",
            "logistics.key",
            "spring.datasource.password");

    /** 占位符特征（小写子串匹配）。 */
    static final List<String> PLACEHOLDER_TOKENS = List.of(
            "your_", "your-", "yourkey", "change_me", "changeme", "change-me",
            "xxx", "placeholder", "todo", "tbd", "__env_injected__", "replace_me", "test_key");

    /** Fail-Fast 开关属性（各 profile 的 yml 中设定）。 */
    private static final String FAIL_FAST_PROPERTY = "clawbot.startup.placeholder-fail-fast";

    private final Environment environment;

    /**
     * 构造器注入（G-14）。
     *
     * @param environment 运行环境
     */
    public PlaceholderConfigValidator(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> problems = findPlaceholderProblems();
        if (problems.isEmpty()) {
            log.info("[SUP-06] 关键配置占位符校验通过（共 {} 项）", CRITICAL_KEYS.size());
            return;
        }
        String joined = String.join("; ", problems);
        if (isFailFast()) {
            throw new IllegalStateException(
                    "关键配置为占位符或缺失，拒绝启动（SUP-06 / AC-F3）: " + joined);
        }
        log.warn("[SUP-06] 关键配置为占位符或缺失，仅告警不阻断（local/test 环境）: {}", joined);
    }

    /**
     * 计算当前环境下的占位符/缺失问题列表。
     *
     * @return 问题项集合（如 {@code llm.api-key=占位符}）；为空表示全部合格
     */
    public List<String> findPlaceholderProblems() {
        List<String> problems = new ArrayList<>();
        for (String key : CRITICAL_KEYS) {
            String value = environment.getProperty(key);
            if (value == null || value.isBlank()) {
                problems.add(key + "=缺失");
            } else if (isPlaceholder(value)) {
                problems.add(key + "=占位符");
            }
        }
        return problems;
    }

    /**
     * 是否启用 Fail-Fast：显式开关为 true，或处于 {@code prod} profile。
     *
     * @return true 表示占位符/缺失将导致启动失败
     */
    public boolean isFailFast() {
        Boolean property = environment.getProperty(FAIL_FAST_PROPERTY, Boolean.class, Boolean.FALSE);
        boolean prodProfile = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        return Boolean.TRUE.equals(property) || prodProfile;
    }

    private static boolean isPlaceholder(String value) {
        String normalized = value.trim().toLowerCase();
        return PLACEHOLDER_TOKENS.stream().anyMatch(normalized::contains);
    }
}
