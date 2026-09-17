package com.lumensteward.clawbot.verification;

import com.lumensteward.clawbot.common.error.ErrorCode;
import com.lumensteward.clawbot.infrastructure.cache.TokenBlacklistService;
import com.lumensteward.clawbot.infrastructure.config.properties.SecurityProperties;
import com.lumensteward.clawbot.infrastructure.persistence.entity.SysAdminUserEntity;
import com.lumensteward.clawbot.infrastructure.security.AuthPrincipal;
import com.lumensteward.clawbot.infrastructure.security.JwtAuthenticationFilter;
import com.lumensteward.clawbot.infrastructure.security.JwtTokenProvider;
import com.lumensteward.clawbot.infrastructure.security.RestAccessDeniedHandler;
import com.lumensteward.clawbot.infrastructure.security.RestAuthenticationEntryPoint;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 独立验证：认证门禁语义（SRS FR-15 / G-08 / AC-E2 / AC-E3）。
 *
 * <p>直接对真实 {@link RestAuthenticationEntryPoint} / {@link RestAccessDeniedHandler} /
 * {@link JwtAuthenticationFilter} 施加断言，证明"无 Token → 401（非 200）"、
 * "越权 → 403"、"非法/已注销 Token 不建立认证"。不依赖数据库。
 */
class SecurityGateVerificationTest {

    private static final String SECRET = "qa-verify-secret-key-0123456789-abcdefghij";

    private JwtTokenProvider provider() {
        return new JwtTokenProvider(new SecurityProperties(SECRET, 12, 5, 15, 300));
    }

    private static SysAdminUserEntity admin(String role) {
        SysAdminUserEntity admin = new SysAdminUserEntity();
        admin.setId(7L);
        admin.setUsername("qa-admin");
        admin.setRole(role);
        admin.setDisplayName("QA");
        admin.setStatus(1);
        return admin;
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("AC-E2：未认证入口点返回 401，响应体 code=20001（非 200）")
    void entryPointReturns401() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new RestAuthenticationEntryPoint().commence(new MockHttpServletRequest(), response, null);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"code\":20001");
    }

    @Test
    @DisplayName("AC-E2：Token 过期时入口点返回 401 且 code=20002")
    void entryPointHonorsExpiredCode() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(JwtAuthenticationFilter.ATTR_ERROR_CODE, ErrorCode.TOKEN_EXPIRED);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new RestAuthenticationEntryPoint().commence(request, response, null);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"code\":20002");
    }

    @Test
    @DisplayName("AC-E3：越权处理器返回 403，响应体 code=20003")
    void deniedHandlerReturns403() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new RestAccessDeniedHandler().handle(new MockHttpServletRequest(), response,
                new AccessDeniedException("forbidden"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("\"code\":20003");
    }

    @Test
    @DisplayName("AC-E2：无 Authorization 头 → 不建立认证")
    void noTokenLeavesUnauthenticated() throws Exception {
        AtomicBoolean chained = new AtomicBoolean(false);
        new JwtAuthenticationFilter(provider(), mock(TokenBlacklistService.class))
                .doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                        (req, res) -> chained.set(true));

        assertThat(chained).isTrue();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("合法 Token → 建立认证并带 ROLE_ 权限")
    void validTokenAuthenticates() throws Exception {
        JwtTokenProvider provider = provider();
        String token = provider.issue(admin("SUPER_ADMIN"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);

        new JwtAuthenticationFilter(provider, mock(TokenBlacklistService.class))
                .doFilter(request, new MockHttpServletResponse(), (req, res) -> { });

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        assertThat(authentication.getAuthorities().stream().map(Object::toString).toList())
                .containsExactly("ROLE_SUPER_ADMIN");
        assertThat(((AuthPrincipal) authentication.getPrincipal()).username()).isEqualTo("qa-admin");
    }

    @Test
    @DisplayName("AC-E2：非法/伪造 Token → 不建立认证，且标记 UNAUTHENTICATED")
    void invalidTokenNotAuthenticated() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer not-a-jwt");

        new JwtAuthenticationFilter(provider(), mock(TokenBlacklistService.class))
                .doFilter(request, new MockHttpServletResponse(), (req, res) -> { });

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(request.getAttribute(JwtAuthenticationFilter.ATTR_ERROR_CODE))
                .isEqualTo(ErrorCode.UNAUTHENTICATED);
    }

    @Test
    @DisplayName("AC-E2/FR-15：已登出（黑名单）Token → 不建立认证，标记 TOKEN_REVOKED")
    void revokedTokenNotAuthenticated() throws Exception {
        JwtTokenProvider provider = provider();
        String token = provider.issue(admin("OPERATOR"));
        Claims claims = provider.parse(token);

        TokenBlacklistService blacklist = mock(TokenBlacklistService.class);
        when(blacklist.isBlacklisted(anyString())).thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);

        new JwtAuthenticationFilter(provider, blacklist)
                .doFilter(request, new MockHttpServletResponse(), (req, res) -> { });

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(request.getAttribute(JwtAuthenticationFilter.ATTR_ERROR_CODE))
                .isEqualTo(ErrorCode.TOKEN_REVOKED);
        assertThat(claims.getId()).isNotBlank();
    }
}
