package com.lumensteward.clawbot.infrastructure.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * LLM 配置（{@code llm.*}，G-16；架构 5.4）。
 *
 * <p>{@code provider} 决定装配 {@code MockLlmClient} 还是 {@code OpenAiCompatibleLlmClient}
 * （AC-D3 零代码切换）。密钥 {@code apiKey} 经环境变量注入，不落明文（BR-20）。
 *
 * @param provider            提供方：mock / real
 * @param baseUrl             real 模式的兼容 OpenAI 服务地址
 * @param apiKey              API Key（经环境变量注入）
 * @param model               对话模型名
 * @param visionModel         多模态视觉模型名（FR-06/10）
 * @param timeoutSeconds      对话调用超时（秒），默认 15s（EI-07）
 * @param visionTimeoutSeconds 视觉调用超时（秒），默认 20s（9.4.1）
 * @param inputBudgetTokens   上下文输入预算 token（FR-04 / Q3）
 * @param reservedOutputTokens 输出预留 token（FR-04 / Q3）
 */
@ConfigurationProperties(prefix = "llm")
public record LlmProperties(
        @DefaultValue("mock") String provider,
        @DefaultValue("") String baseUrl,
        @DefaultValue("") String apiKey,
        @DefaultValue("mock-model") String model,
        @DefaultValue("") String visionModel,
        @DefaultValue("15") int timeoutSeconds,
        @DefaultValue("20") int visionTimeoutSeconds,
        @DefaultValue("8000") int inputBudgetTokens,
        @DefaultValue("1000") int reservedOutputTokens) {
}
