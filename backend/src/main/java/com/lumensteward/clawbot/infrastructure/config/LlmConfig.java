package com.lumensteward.clawbot.infrastructure.config;

import com.lumensteward.clawbot.infrastructure.config.properties.LlmProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * LLM 客户端配置（G-16 Bean 声明收敛）。
 *
 * <p>装配 LLM 调用所需的 {@link RestClient}：当 {@code llm.base-url} 非空时设置基址（real 模式）。
 * T03 的 {@code LlmClientFactory} 将按 {@code llm.provider} 装配 {@code MockLlmClient} /
 * {@code OpenAiCompatibleLlmClient}（AC-D3 零代码改动）。
 *
 * <p>此处不发起任何网络调用，仅声明 Bean；连通性与鉴权有效性由 {@code StartupDoctor}（SUP-05）探测。
 */
@Configuration
public class LlmConfig {

    /** LLM 配置（构造器注入，G-14）。 */
    private final LlmProperties properties;

    /**
     * 构造器注入。
     *
     * @param properties LLM 配置
     */
    public LlmConfig(LlmProperties properties) {
        this.properties = properties;
    }

    /**
     * LLM 对话/视觉专用出站客户端。
     *
     * @param builder 共享的 RestClient.Builder（含超时与 traceId 透传）
     * @return LLM RestClient
     */
    @Bean
    public RestClient llmRestClient(RestClient.Builder builder) {
        RestClient.Builder customized = builder.clone();
        String baseUrl = properties.baseUrl();
        if (baseUrl != null && !baseUrl.isBlank()) {
            customized.baseUrl(baseUrl);
        }
        return customized.build();
    }
}
