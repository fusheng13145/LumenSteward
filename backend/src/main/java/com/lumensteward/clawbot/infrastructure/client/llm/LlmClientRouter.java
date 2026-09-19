package com.lumensteward.clawbot.infrastructure.client.llm;

import com.lumensteward.clawbot.application.config.ConfigKeys;
import com.lumensteward.clawbot.application.config.DynamicConfigService;
import com.lumensteward.clawbot.domain.port.model.VisionRequest;
import com.lumensteward.clawbot.domain.port.model.VisionResult;
import com.lumensteward.clawbot.infrastructure.client.llm.dto.ChatRequest;
import com.lumensteward.clawbot.infrastructure.client.llm.dto.ChatResult;
import com.lumensteward.clawbot.infrastructure.client.llm.exception.LlmException;
import com.lumensteward.clawbot.infrastructure.client.llm.exception.LlmUnavailableException;
import com.lumensteward.clawbot.infrastructure.config.properties.LlmProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * LLM 客户端运行时路由器（B-2 / 迭代 2 T13）。
 *
 * <p>MVP 中 {@code MockLlmClient} / {@code OpenAiCompatibleLlmClient} 由
 * {@code @ConditionalOnExpression("${llm.provider}")} 在<b>启动期</b>二选一装配，切换 provider 必须重启。
 * 本路由器把它们收拢为按 {@code sys_config} 的 {@code llm.provider} <b>运行时</b>选择的单一入口，
 * 使"改配置即切换"成立（B-2：多模型 A/B 可切换）。
 *
 * <p><b>诚实降级（BR-04）：</b>目标 provider 的客户端<b>未装配</b>时（例如以 {@code mock} 启动，
 * 却把配置改成 {@code openai-compatible}），路由器<b>不假装切换成功</b>——以 WARN 记录并回退当前
 * 唯一可用实现，{@link #provider()} 始终返回<b>真实生效</b>的实现标识，便于日志与回放台核对真相。
 * 若一个实现都不可用，抛 {@link LlmUnavailableException} 交编排器走降级矩阵（SRS 9.5）。
 */
@Component
@Primary
public class LlmClientRouter implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClientRouter.class);

    private final Map<String, LlmClient> delegates = new LinkedHashMap<>();
    private final DynamicConfigService dynamicConfig;
    private final LlmProperties properties;

    /**
     * Spring 装配用构造器（G-14）：收集<b>已装配</b>的具体实现。
     *
     * <p>注入具体类型而非 {@link LlmClient} 列表，避免把自身收进候选集造成循环依赖。
     *
     * @param mockProvider Mock 实现提供者（条件装配，可能为空）
     * @param realProvider OpenAI 兼容实现提供者（条件装配，可能为空）
     * @param dynamicConfig 动态配置源（运行时 provider）
     * @param properties   静态配置（兜底 provider）
     */
    @Autowired
    public LlmClientRouter(ObjectProvider<MockLlmClient> mockProvider,
                           ObjectProvider<OpenAiCompatibleLlmClient> realProvider,
                           DynamicConfigService dynamicConfig,
                           LlmProperties properties) {
        this.dynamicConfig = dynamicConfig;
        this.properties = properties;
        MockLlmClient mock = mockProvider == null ? null : mockProvider.getIfAvailable();
        if (mock != null) {
            delegates.put(MockLlmClient.PROVIDER, mock);
        }
        OpenAiCompatibleLlmClient real = realProvider == null ? null : realProvider.getIfAvailable();
        if (real != null) {
            delegates.put(OpenAiCompatibleLlmClient.PROVIDER, real);
            delegates.put("real", real);
        }
        log.info("LlmClientRouter 装配完成，可用实现={}（运行时按 {} 路由）",
                delegates.keySet(), ConfigKeys.LLM_PROVIDER);
    }

    /**
     * 测试友好构造：直接给出候选实现（键为 provider 名）。
     *
     * @param candidates    候选实现（至少一项）
     * @param dynamicConfig 动态配置源（可为 null）
     * @param properties    静态配置
     */
    public LlmClientRouter(Map<String, LlmClient> candidates, DynamicConfigService dynamicConfig,
                           LlmProperties properties) {
        if (candidates != null) {
            delegates.putAll(candidates);
        }
        this.dynamicConfig = dynamicConfig;
        this.properties = properties;
    }

    @Override
    public ChatResult chat(ChatRequest request) throws LlmException {
        return resolve().chat(request);
    }

    @Override
    public VisionResult vision(VisionRequest request) throws LlmException {
        return resolve().vision(request);
    }

    @Override
    public String provider() {
        return resolve().provider();
    }

    /**
     * 按运行时配置选择实现（缺失时回退并告警，绝不静默）。
     *
     * @return 实际生效的实现
     */
    public LlmClient resolve() {
        if (delegates.isEmpty()) {
            throw new LlmUnavailableException("无可用 LLM 实现（未装配任何 LlmClient）");
        }
        String configured = configuredProvider();
        LlmClient matched = delegates.get(configured);
        if (matched != null) {
            return matched;
        }
        LlmClient fallback = delegates.values().iterator().next();
        log.warn("配置的 llm.provider={} 未装配对应实现（当前可用: {}），已回退到 {}——"
                        + "真实生效 provider 与之不一致，请核对启动参数",
                configured, delegates.keySet(), fallback.provider());
        return fallback;
    }

    /**
     * 运行时期望的 provider（动态配置优先，回退启动期静态值）。
     *
     * @return provider 名
     */
    public String configuredProvider() {
        if (dynamicConfig != null) {
            return dynamicConfig.getString(ConfigKeys.LLM_PROVIDER, properties.provider());
        }
        return properties.provider();
    }

    /**
     * 当前已装配的实现标识（供诊断/回放台核对）。
     *
     * @return provider 名列表
     */
    public List<String> availableProviders() {
        return List.copyOf(delegates.keySet());
    }
}
