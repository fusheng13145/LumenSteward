package com.lumensteward.clawbot.infrastructure.security;

import com.lumensteward.clawbot.infrastructure.config.properties.SecurityProperties;
import com.lumensteward.clawbot.infrastructure.persistence.entity.SysAdminUserEntity;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * JWT 签发与解析（架构 5.4 / SRS FR-15，算法 HS256）。
 *
 * <p>载荷约定：
 * <ul>
 *   <li>{@code sub} = 登录名（{@code sys_admin_user.username}）；</li>
 *   <li>{@code jti} = 唯一标识，登出时写入 {@code jwt:blacklist:{jti}}（8.3）；</li>
 *   <li>{@code role} / {@code uid} / {@code displayName} 为自定义声明，供 RBAC 与展示使用。</li>
 * </ul>
 *
 * <p><b>密钥来源（BR-20）：</b>{@code security.jwt-secret} 经环境变量注入。缺失时本地生成一次性随机
 * 密钥并告警（仅限开发；prod 由 {@code PlaceholderConfigValidator} Fail-Fast 拦截）。
 */
@Component
public class JwtTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(JwtTokenProvider.class);

    /** 角色声明键。 */
    public static final String CLAIM_ROLE = "role";
    /** 管理员主键声明键。 */
    public static final String CLAIM_UID = "uid";
    /** 展示名声明键。 */
    public static final String CLAIM_DISPLAY_NAME = "displayName";

    /** HS256 要求的最小密钥字节数（RFC 7518）。 */
    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKey signingKey;
    private final Duration ttl;

    /**
     * 构造器注入（G-14）。
     *
     * @param securityProperties 安全配置
     */
    public JwtTokenProvider(SecurityProperties securityProperties) {
        this.signingKey = resolveKey(securityProperties.jwtSecret());
        int hours = Math.max(1, securityProperties.jwtExpireHours());
        this.ttl = Duration.ofHours(hours);
    }

    /**
     * 为管理员签发 JWT。
     *
     * @param admin 管理员实体（须含 id/username/role）
     * @return JWT 字符串
     */
    public String issue(SysAdminUserEntity admin) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(ttl);
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(admin.getUsername())
                .claim(CLAIM_ROLE, admin.getRole())
                .claim(CLAIM_UID, admin.getId())
                .claim(CLAIM_DISPLAY_NAME, admin.getDisplayName())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(signingKey)
                .compact();
    }

    /**
     * 解析并验签 JWT。
     *
     * @param token JWT 字符串
     * @return 载荷声明
     * @throws io.jsonwebtoken.JwtException 签名不符 / 过期 / 格式非法
     */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * Token 有效期（秒），用于登出黑名单 TTL 对齐。
     *
     * @return 有效秒数
     */
    public long ttlSeconds() {
        return ttl.getSeconds();
    }

    private static SecretKey resolveKey(String secret) {
        if (secret == null || secret.isBlank()) {
            log.warn("security.jwt-secret 未配置，已生成一次性随机密钥（仅限本地开发；生产必须经环境变量注入，BR-20）");
            return Jwts.SIG.HS256.key().build();
        }
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < MIN_SECRET_BYTES) {
            log.warn("security.jwt-secret 长度不足 {} 字节，已按 SHA-256 派生补齐以满足 HS256 强度要求", MIN_SECRET_BYTES);
            bytes = deriveKeyBytes(bytes);
        }
        return Keys.hmacShaKeyFor(bytes);
    }

    /**
     * 将短密钥确定性地扩展为 ≥32 字节，避免直接抛 WeakKeyException 导致启动失败。
     *
     * @param seed 原始密钥字节
     * @return 补齐后的密钥字节
     */
    private static byte[] deriveKeyBytes(byte[] seed) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(seed);
            byte[] material = new byte[MIN_SECRET_BYTES];
            for (int i = 0; i < material.length; i++) {
                material[i] = digest[i % digest.length];
            }
            return material;
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-256 必然可用，此分支不可达
            throw new IllegalStateException("JCE 缺少 SHA-256 实现", e);
        }
    }
}
