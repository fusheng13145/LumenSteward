package com.lumensteward.clawbot.security;

import com.lumensteward.clawbot.common.error.ErrorCode;
import com.lumensteward.clawbot.infrastructure.cache.TokenBlacklistService;
import com.lumensteward.clawbot.infrastructure.config.properties.SecurityProperties;
import com.lumensteward.clawbot.infrastructure.persistence.entity.SysAdminUserEntity;
import com.lumensteward.clawbot.infrastructure.security.AuthPrincipal;
import com.lumensteward.clawbot.infrastructure.security.JwtAuthenticationFilter;
import com.lumensteward.clawbot.infrastructure.security.JwtTokenProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link JwtAuthenticationFilter} 单测（AC-E2 无 Token = 未认证 / AC-E8 越权隔离）。
 *
 * <p>断言「不设认证」而非「返回 200」——过滤器只负责填充安全上下文，401 由入口点产出；
 * 二者合力保证「无 Token 访问受保护接口必须 401」。
 */
class JwtAuthenticationFilterTest {

    private static final String SECRET = "lumensteward-clawbot-test-secret-0123456789";

    private JwtTokenProvider jwtTokenProvider;
    private TokenBlacklistService tokenBlacklistService;
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        jwtTokenProvider = new JwtTokenProvider(new SecurityProperties(SECRET, 12, 5, 15, 300));
        tokenBlacklistService = mock(TokenBlacklistService.class);
        filter = new JwtAuthenticationFilter(jwtTokenProvider, tokenBlacklistService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static SysAdminUserEntity admin() {
        SysAdminUserEntity admin = new SysAdminUserEntity();
        admin.setId(1L);
        admin.setUsername("superadmin");
        admin.setRole("SUPER_ADMIN");
        admin.setDisplayName("超级管理员");
        return admin;
    }

    @Test
    @DisplayName("AC-E2：无 Authorization 头时不设置任何认证（交由入口点返回 401）")
    void shouldNotAuthenticateWithoutToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(request.getAttribute(JwtAuthenticationFilter.ATTR_ERROR_CODE)).isNull();
    }

    @Test
    @DisplayName("合法 Token：设置 AuthPrincipal 与 ROLE_SUPER_ADMIN 权限")
    void shouldAuthenticateWithValidToken() throws Exception {
        String token = jwtTokenProvider.issue(admin());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication).isNotNull();
        assertThat(authentication.getPrincipal()).isInstanceOf(AuthPrincipal.class);
        AuthPrincipal principal = (AuthPrincipal) authentication.getPrincipal();
        assertThat(principal.username()).isEqualTo("superadmin");
        assertThat(principal.adminRole()).isPresent();
        assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_SUPER_ADMIN");
    }

    @Test
    @DisplayName("已登出（黑名单命中）：不认证，错误码记为 TOKEN_REVOKED（20007）")
    void shouldRejectBlacklistedToken() throws Exception {
        String token = jwtTokenProvider.issue(admin());
        when(tokenBlacklistService.isBlacklisted(anyString())).thenReturn(true);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(request.getAttribute(JwtAuthenticationFilter.ATTR_ERROR_CODE))
                .isEqualTo(ErrorCode.TOKEN_REVOKED);
    }

    @Test
    @DisplayName("非法 Token：不认证，错误码记为 UNAUTHENTICATED（20001）")
    void shouldRejectMalformedToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer not-a-real-jwt");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        assertThat(request.getAttribute(JwtAuthenticationFilter.ATTR_ERROR_CODE))
                .isEqualTo(ErrorCode.UNAUTHENTICATED);
    }

    @Test
    @DisplayName("非 Bearer 前缀：不认证（避免误放行）")
    void shouldIgnoreNonBearerScheme() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Basic abc");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
