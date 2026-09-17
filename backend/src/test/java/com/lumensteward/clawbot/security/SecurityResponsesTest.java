package com.lumensteward.clawbot.security;

import com.lumensteward.clawbot.common.error.ErrorCode;
import com.lumensteward.clawbot.infrastructure.security.JwtAuthenticationFilter;
import com.lumensteward.clawbot.infrastructure.security.RestAccessDeniedHandler;
import com.lumensteward.clawbot.infrastructure.security.RestAuthenticationEntryPoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 401 / 403 响应契约单测（G-08 / AC-E2 / AC-E3）。
 *
 * <p>关键验收：未认证返回 <b>HTTP 401</b>（<b>不是</b> 200）；越权返回 <b>HTTP 403</b>，
 * 且响应体为统一 {@code ApiResponse}（含业务码与 traceId 字段）。
 */
class SecurityResponsesTest {

    @Test
    @DisplayName("AC-E2：无 Token 访问 → HTTP 401 + code 20001（绝非 200）")
    void shouldReturn401WhenUnauthenticated() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        new RestAuthenticationEntryPoint().commence(request, response,
                new InsufficientAuthenticationException("no token"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"code\":20001");
    }

    @Test
    @DisplayName("AC-E8：已登出 Token → HTTP 401 + code 20007")
    void shouldReturn401WithRevokedCodeWhenAttributeSet() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(JwtAuthenticationFilter.ATTR_ERROR_CODE, ErrorCode.TOKEN_REVOKED);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new RestAuthenticationEntryPoint().commence(request, response,
                new InsufficientAuthenticationException("revoked"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("\"code\":20007");
    }

    @Test
    @DisplayName("AC-E3：权限不足 → HTTP 403 + code 20003")
    void shouldReturn403WhenForbidden() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        new RestAccessDeniedHandler().handle(new MockHttpServletRequest(), response,
                new AccessDeniedException("denied"));

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("\"code\":20003");
    }
}
