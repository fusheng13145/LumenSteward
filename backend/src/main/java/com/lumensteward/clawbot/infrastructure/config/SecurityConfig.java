package com.lumensteward.clawbot.infrastructure.config;

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

/**
 * 后台安全配置（G-16 Bean 声明收敛）。
 *
 * <p>职责：
 * <ul>
 *   <li>无状态（JWT）过滤器链；关闭表单登录与 HTTP Basic；</li>
 *   <li>公开端点：微信回调、登录、actuator 探测、API 文档；其余一律要求认证；</li>
 *   <li>暴露 {@link PasswordEncoder}（BCrypt，供初始管理员口令注入与登录校验复用）。</li>
 * </ul>
 *
 * <p><b>MVP 边界（G-33 如实标注）：</b>JWT 解析过滤器（{@code JwtAuthenticationFilter}）与
 * 登录/越权处理在 T03/T05 接入；接入前 {@code /api/doctor} 暂列为 permitAll，接入后应移入认证区。
 * CORS 复用 {@code WebMvcConfig} 的 MVC 配置（Spring Security 通过 HandlerMappingIntrospector 自动读取）。
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /** 无需认证即可访问的端点（MVP 骨架，T03/T05 将收紧 {code /api/doctor}）。 */
    private static final String[] PUBLIC_ENDPOINTS = {
            "/actuator/health/**",
            "/actuator/info",
            "/actuator/prometheus",
            "/api/wx/callback/**",
            "/api/auth/login",
            "/api/doctor",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/error"
    };

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
                // 纯 API 服务，禁用表单登录与 Basic，避免产生无关的默认行为
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable);
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
