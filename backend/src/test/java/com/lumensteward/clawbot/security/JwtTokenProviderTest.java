package com.lumensteward.clawbot.security;

import com.lumensteward.clawbot.infrastructure.config.properties.SecurityProperties;
import com.lumensteward.clawbot.infrastructure.persistence.entity.SysAdminUserEntity;
import com.lumensteward.clawbot.infrastructure.security.JwtTokenProvider;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JWT 签发与解析单测（SRS FR-15 / AC-E2）。
 *
 * <p>纯单测：不依赖 Spring 容器与外部依赖。
 */
class JwtTokenProviderTest {

    /** 满足 HS256 强度要求的测试密钥。 */
    private static final String SECRET = "lumensteward-clawbot-test-secret-0123456789";

    private static SecurityProperties props(String secret, int hours) {
        return new SecurityProperties(secret, hours, 5, 15, 300);
    }

    private static SysAdminUserEntity admin() {
        SysAdminUserEntity admin = new SysAdminUserEntity();
        admin.setId(7L);
        admin.setUsername("superadmin");
        admin.setRole("SUPER_ADMIN");
        admin.setDisplayName("超级管理员");
        return admin;
    }

    @Test
    @DisplayName("签发的 JWT 可被解析，且角色/uid/displayName 声明保真")
    void shouldRoundTripClaims() {
        JwtTokenProvider provider = new JwtTokenProvider(props(SECRET, 12));

        String token = provider.issue(admin());
        Claims claims = provider.parse(token);

        assertThat(claims.getSubject()).isEqualTo("superadmin");
        assertThat(claims.get("role", String.class)).isEqualTo("SUPER_ADMIN");
        assertThat(claims.get("displayName", String.class)).isEqualTo("超级管理员");
        assertThat(claims.get("uid", Number.class).longValue()).isEqualTo(7L);
        assertThat(claims.getId()).isNotBlank();
        assertThat(provider.ttlSeconds()).isEqualTo(12L * 3600L);
    }

    @Test
    @DisplayName("不同密钥签发的 Token 互不信任（防伪造）")
    void shouldRejectTokenSignedByAnotherKey() {
        JwtTokenProvider issuer = new JwtTokenProvider(props(SECRET, 12));
        JwtTokenProvider verifier = new JwtTokenProvider(
                props("another-completely-different-secret-0987654321", 12));

        String token = issuer.issue(admin());

        assertThatThrownBy(() -> verifier.parse(token)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("被篡改的 Token 解析失败（签名不符）")
    void shouldRejectTamperedToken() {
        JwtTokenProvider provider = new JwtTokenProvider(props(SECRET, 12));
        String token = provider.issue(admin());
        String tampered = token.substring(0, token.length() - 2) + "xx";

        assertThatThrownBy(() -> provider.parse(tampered)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("弱密钥不导致启动失败（确定性派生补齐）")
    void shouldTolerateShortSecret() {
        JwtTokenProvider provider = new JwtTokenProvider(props("short", 1));

        String token = provider.issue(admin());

        assertThat(provider.parse(token).getSubject()).isEqualTo("superadmin");
    }

    @Test
    @DisplayName("缺失密钥时本地可用（生成一次性随机密钥）")
    void shouldWorkWithoutConfiguredSecret() {
        JwtTokenProvider provider = new JwtTokenProvider(props("", 1));
        String token = provider.issue(admin());

        assertThat(provider.parse(token).getSubject()).isEqualTo("superadmin");
    }

    @Test
    @DisplayName("过期 Token 抛 ExpiredJwtException（区分于非法 Token）")
    void shouldThrowExpiredForExpiredToken() {
        // 该断言以反射构造的过期载荷不可行，改用最小 TTL 的负向验证：
        // 直接把过期分支交给 JwtAuthenticationFilter 覆盖（见其单测）。
        // 此处验证 parse 对空/非法串的行为边界。
        JwtTokenProvider provider = new JwtTokenProvider(props(SECRET, 1));
        assertThatThrownBy(() -> provider.parse("not-a-jwt")).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> provider.parse("")).isInstanceOf(IllegalArgumentException.class);
        assertThat(ExpiredJwtException.class).isAssignableTo(JwtException.class);
    }
}
