package com.lumensteward.clawbot.infrastructure.config;

import com.lumensteward.clawbot.infrastructure.security.JwtAuthenticationFilter;
import com.lumensteward.clawbot.infrastructure.security.RestAccessDeniedHandler;
import com.lumensteward.clawbot.infrastructure.security.RestAuthenticationEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * 后台安全配置（G-16 Bean 声明收敛；架构 4.3 / 5.4）。
 *
 * <p>职责：
 * <ul>
 *   <li>无状态（JWT）过滤器链；关闭表单登录与 HTTP Basic；</li>
 *   <li>公开端点：微信回调、登录、actuator 探测、API 文档；<b>其余一律要求认证</b>（AC-E2）；</li>
 *   <li>未认证 → {@link RestAuthenticationEntryPoint}（HTTP 401）；越权 → {@link RestAccessDeniedHandler}（HTTP 403）；</li>
 *   <li>暴露 {@link PasswordEncoder}（BCrypt，供初始管理员口令注入与登录校验复用）。</li>
 * </ul>
 *
 * <p>CORS 复用 {@code WebMvcConfig} 的 MVC 配置（Spring Security 通过 HandlerMappingIntrospector 自动读取）。
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /** 无需认证即可访问的端点（T05 已收紧 {@code /api/doctor} 至认证区）。 */
    private static final String[] PUBLIC_ENDPOINTS = {
            "/actuator/health/**",
            "/actuator/info",
            "/actuator/prometheus",
            "/api/wx/callback/**",
            "/api/auth/login",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/error"
    };

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;

    /**
     * 构造器注入（G-14）。
     *
     * @param jwtAuthenticationFilter JWT 认证过滤器
     * @param authenticationEntryPoint 未认证入口点（401）
     * @param accessDeniedHandler     越权处理器（403）
     */
    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          RestAuthenticationEntryPoint authenticationEntryPoint,
                          RestAccessDeniedHandler accessDeniedHandler) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.authenticationEntryPoint = authenticationEntryPoint;
        this.accessDeniedHandler = accessDeniedHandler;
    }

    /**
     * 无状态安全过滤器链。
     *
     * @param http HttpSecurity 构建器
     * @return 过滤器链
     * @throws Exception 构建异常
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // 前后端分离 + 无状态 Token，无 CSRF 会话风险，故关闭
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                        .anyRequest().authenticated())
                // 401 / 403 统一走统一响应体（G-08），避免容器默认页或裸 403
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                // 纯 API 服务，禁用表单登录与 Basic，避免产生无关的默认行为
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                // JWT 过滤器置于用户名口令过滤器之前（无状态，无 session）
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * 口令编码器（BCrypt，含盐）。
     *
     * @return BCrypt 编码器
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
