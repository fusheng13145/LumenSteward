package com.lumensteward.clawbot.infrastructure.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Arrays;

/**
 * 初始管理员口令注入器（Q7 / BR-20 / SUP-06，架构 3.5）。
 *
 * <p>口令来源一律为环境变量 {@code ADMIN_INIT_PASSWORD}，<b>任何路径都不硬编码明文口令</b>：
 * <ul>
 *   <li>环境变量存在 → 直接使用；</li>
 *   <li>缺失且 Fail-Fast（prod）→ 抛异常拒绝启动；</li>
 *   <li>缺失且 local/test → 生成一次性随机口令并打印到控制台（WARN 级），提示尽快登录并修改。</li>
 * </ul>
 */
@Component
public class AdminInitializer {

    private static final Logger log = LoggerFactory.getLogger(AdminInitializer.class);

    /** 环境变量键：初始管理员口令。 */
    static final String ENV_ADMIN_INIT_PASSWORD = "ADMIN_INIT_PASSWORD";

    /** 占位口令哈希标记（与 V1.0.4__seed_admin.sql 保持一致，非任何真实口令的哈希）。 */
    public static final String PLACEHOLDER_HASH = "__ENV_INJECTED__";

    /** Fail-Fast 开关属性（与 PlaceholderConfigValidator 共用）。 */
    private static final String FAIL_FAST_PROPERTY = "clawbot.startup.placeholder-fail-fast";

    /** 一次性口令字符集（去除易混淆字符）。 */
    private static final char[] PASSWORD_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789".toCharArray();

    /** 一次性口令长度。 */
    private static final int ONE_TIME_PASSWORD_LENGTH = 16;

    private final PasswordEncoder passwordEncoder;
    private final Environment environment;
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 构造器注入（G-14）。
     *
     * @param passwordEncoder 口令编码器（BCrypt）
     * @param environment     运行环境
     */
    public AdminInitializer(PasswordEncoder passwordEncoder, Environment environment) {
        this.passwordEncoder = passwordEncoder;
        this.environment = environment;
    }

    /**
     * 解析初始管理员明文口令。
     *
     * @return 明文口令（来自环境变量，或本地生成的一次性随机口令）
     * @throws IllegalStateException prod 下环境变量缺失时抛出（Fail-Fast）
     */
    public String resolveInitialPassword() {
        String configured = environment.getProperty(ENV_ADMIN_INIT_PASSWORD);
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        if (isFailFast()) {
            throw new IllegalStateException(
                    "prod 下缺少环境变量 " + ENV_ADMIN_INIT_PASSWORD + "，拒绝启动（BR-20 / SUP-06）");
        }
        String generated = generateOneTimePassword();
        log.warn("未配置环境变量 {}，已为初始管理员生成一次性口令（仅限本地开发，请立即登录并修改）：{}",
                ENV_ADMIN_INIT_PASSWORD, generated);
        return generated;
    }

    /**
     * 计算口令的 BCrypt 哈希。
     *
     * @param rawPassword 明文口令
     * @return BCrypt 哈希（含盐）
     */
    public String encode(String rawPassword) {
        return passwordEncoder.encode(rawPassword);
    }

    /**
     * 是否 Fail-Fast：显式开关为 true，或处于 {@code prod} profile。
     *
     * @return true 表示缺失口令即拒绝启动
     */
    public boolean isFailFast() {
        Boolean property = environment.getProperty(FAIL_FAST_PROPERTY, Boolean.class, Boolean.FALSE);
        boolean prodProfile = Arrays.asList(environment.getActiveProfiles()).contains("prod");
        return Boolean.TRUE.equals(property) || prodProfile;
    }

    private String generateOneTimePassword() {
        StringBuilder builder = new StringBuilder(ONE_TIME_PASSWORD_LENGTH);
        for (int i = 0; i < ONE_TIME_PASSWORD_LENGTH; i++) {
            builder.append(PASSWORD_ALPHABET[secureRandom.nextInt(PASSWORD_ALPHABET.length)]);
        }
        return builder.toString();
    }
}
