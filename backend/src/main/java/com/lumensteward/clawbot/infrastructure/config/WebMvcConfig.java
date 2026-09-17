package com.lumensteward.clawbot.infrastructure.config;

import com.lumensteward.clawbot.infrastructure.observability.TraceIdFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置（G-16 配置类收敛）。
 *
 * <p>当前仅负责跨域（后台前端本地开发 5173 → 后端 8080；生产由 Nginx 同域代理规避跨域，
 * NFR-PO-01），并把响应头 {@code X-Trace-Id} 暴露给浏览器（G-09）。
 *
 * <p>限流拦截器（{@code RateLimitInterceptor}）等横切组件将在 T03 落地后于此处注册，以保持
 * 「Bean 声明收敛于 config 包」的约定。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /** 允许的前端来源，逗号分隔；来自配置，禁止硬编码生产域名。 */
    private final String[] allowedOrigins;

    /**
     * 构造器注入（G-14 统一构造器注入，保证不可变）。
     *
     * @param allowedOrigins 允许的来源，逗号分隔
     */
    public WebMvcConfig(
            @Value("${clawbot.cors.allowed-origins:http://localhost:5173}") String allowedOrigins) {
        this.allowedOrigins = allowedOrigins.split("\\s*,\\s*");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders(TraceIdFilter.TRACE_ID_HEADER)
                .allowCredentials(true)
                .maxAge(3600L);
    }
}
