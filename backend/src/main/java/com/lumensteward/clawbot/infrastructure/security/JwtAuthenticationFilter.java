package com.lumensteward.clawbot.infrastructure.security;

import com.lumensteward.clawbot.common.error.ErrorCode;
import com.lumensteward.clawbot.infrastructure.cache.TokenBlacklistService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * JWT 认证过滤器（架构 5.4 / SRS FR-15 / AC-E2/E8）。
 *
 * <p>职责：从 {@code Authorization: Bearer <token>} 解析并验签；命中黑名单（已登出）或过期/非法时
 * <b>不设置</b>认证，仅写入请求属性 {@link #ATTR_ERROR_CODE}，交由
 * {@link RestAuthenticationEntryPoint} 统一返回 401，从而保证「无 Token = 401」（AC-E2）。
 *
 * <p>全程无状态，不创建会话（与 {@code SecurityConfig} 的 STATELESS 策略一致）。
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /** 认证失败原因在请求属性中的键，供入口点读取具体错误码。 */
    public static final String ATTR_ERROR_CODE = "clawbot.auth.errorCode";

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtTokenProvider jwtTokenProvider;
    private final TokenBlacklistService tokenBlacklistService;

    /**
     * 构造器注入（G-14）。
     *
     * @param jwtTokenProvider     JWT 提供者
     * @param tokenBlacklistService 黑名单服务
     */
    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider,
                                   TokenBlacklistService tokenBlacklistService) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.tokenBlacklistService = tokenBlacklistService;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader(HEADER);
        if (header != null && header.startsWith(PREFIX)
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            authenticate(request, header.substring(PREFIX.length()).trim());
        }
        filterChain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest request, String token) {
        if (token.isEmpty()) {
            request.setAttribute(ATTR_ERROR_CODE, ErrorCode.UNAUTHENTICATED);
            return;
        }
        try {
            Claims claims = jwtTokenProvider.parse(token);
            String jti = claims.getId();
            if (jti != null && tokenBlacklistService.isBlacklisted(jti)) {
                // 已登出：Token 失效（20007），不得放行
                request.setAttribute(ATTR_ERROR_CODE, ErrorCode.TOKEN_REVOKED);
                return;
            }
            AuthPrincipal principal = new AuthPrincipal(
                    toLong(claims.get(JwtTokenProvider.CLAIM_UID)),
                    claims.getSubject(),
                    claims.get(JwtTokenProvider.CLAIM_ROLE, String.class),
                    claims.get(JwtTokenProvider.CLAIM_DISPLAY_NAME, String.class),
                    jti);
            String authority = principal.authority();
            List<SimpleGrantedAuthority> authorities = authority == null
                    ? List.of()
                    : List.of(new SimpleGrantedAuthority(authority));
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(principal, null, authorities);
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (ExpiredJwtException e) {
            request.setAttribute(ATTR_ERROR_CODE, ErrorCode.TOKEN_EXPIRED);
        } catch (JwtException | IllegalArgumentException e) {
            request.setAttribute(ATTR_ERROR_CODE, ErrorCode.UNAUTHENTICATED);
        }
    }

    private static Long toLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
