package com.lumensteward.clawbot.infrastructure.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * OpenAPI 3 文档配置（TODO-09 / NFR-SE-06）。
 *
 * <p>仅在非生产环境启用（{@code @Profile("!prod")}）：生产不暴露接口文档。声明 JWT Bearer 认证
 * 方案，便于在 Swagger UI 中携带 Token 调试受 RBAC 保护的后台接口。
 */
@Configuration
@Profile("!prod")
public class OpenApiConfig {

    /**
     * 后台 API 文档定义。
     *
     * @return OpenAPI 模型
     */
    @Bean
    public OpenAPI clawbotOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("衔光管家（LumenSteward）微信 Claw 助手 · 后台 API")
                        .description("MVP 管理后台接口。仅非生产环境开放（NFR-SE-06）。"
                                + "统一响应体 { code, message, data, traceId, timestamp }。")
                        .version("v1.0"))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
