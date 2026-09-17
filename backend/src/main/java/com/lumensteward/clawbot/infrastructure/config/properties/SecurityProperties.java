package com.lumensteward.clawbot.infrastructure.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 后台安全配置（{@code security.*}，G-16；架构 5.4）。
 *
 * <p>用于后台 JWT 鉴权与登录失败锁定（FR-15）。{@code jwtSecret} 经环境变量注入（BR-20），
 * prod 下若为占位/缺失由 {@code PlaceholderConfigValidator} Fail-Fast。
 *
 * @param jwtSecret               JWT 签名密钥（经环境变量注入）
 * @param jwtExpireHours          Token 有效期（小时）
 * @param lockThreshold           连续失败达此次数即锁定（FR-15）
 * @param lockMinutes             锁定时长（分钟）
 * @param loginFailWindowSeconds  失败计数时间窗（秒）
 */
@ConfigurationProperties(prefix = "security")
public record SecurityProperties(
        @DefaultValue("") String jwtSecret,
        @DefaultValue("12") int jwtExpireHours,
        @DefaultValue("5") int lockThreshold,
        @DefaultValue("15") int lockMinutes,
        @DefaultValue("300") int loginFailWindowSeconds) {
}
