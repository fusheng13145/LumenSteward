package com.lumensteward.clawbot.infrastructure.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 初始管理员引导配置（{@code admin.bootstrap.*}，G-16；架构 5.4 / Q7）。
 *
 * <p>仅承载初始管理员的登录名；口令一律来自环境变量 {@code ADMIN_INIT_PASSWORD}，
 * 不在此留存任何口令（BR-20）。
 *
 * @param username 初始管理员登录名（默认 superadmin）
 */
@ConfigurationProperties(prefix = "admin.bootstrap")
public record AdminBootstrapProperties(
        @DefaultValue("superadmin") String username) {
}
