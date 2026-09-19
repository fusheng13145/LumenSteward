package com.lumensteward.clawbot.infrastructure.client.llm;

import com.lumensteward.clawbot.application.config.ConfigKeys;
import com.lumensteward.clawbot.application.config.DynamicConfigService;
import com.lumensteward.clawbot.infrastructure.client.llm.dto.ChatRequest;
import com.lumensteward.clawbot.infrastructure.client.llm.dto.ChatResult;
import com.lumensteward.clawbot.infrastructure.client.llm.exception.LlmUnavailableException;
import com.lumensteward.clawbot.infrastructure.config.properties.LlmProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * LLM 客户端路由单测（B-2 / T13）。
 *
 * <p>关键契约：按运行时 {@code llm.provider} 路由；目标实现<b>未装配</b>时回退并记录告警，
 * 且 {@code provider()} 始终返回<b>真实生效</b>的实现——不谎报切换成功。
 */
class LlmClientRouterTest {

    private static final LlmProperties PROPS =
            new LlmProperties("mock", "", "", "mock-model", "", 15, 20, 8000, 1000);

    private final DynamicConfigService config = mock(DynamicConfigService.class);

    @Test
    @DisplayName("运行时 provider=mock → 命中 Mock 实现")
    void shouldRouteToMock() {
        LlmClient mockClient = client("mock");
        LlmClientRouter router = router(Map.of("mock", mockClient));
        when(config.getString(ConfigKeys.LLM_PROVIDER, "mock")).thenReturn("mock");

        assertThat(router.resolve()).isSameAs(mockClient);
        assertThat(router.provider()).isEqualTo("mock");
    }

    @Test
    @DisplayName("B-2 AC② provider=openai-compatible 且已装配 → 命中真实实现")
    void shouldRouteToRealWhenAvailable() {
        LlmClient mockClient = client("mock");
        LlmClient realClient = client("openai-compatible");
        LlmClientRouter router = router(Map.of("mock", mockClient, "openai-compatible", realClient));
        when(config.getString(ConfigKeys.LLM_PROVIDER, "mock")).thenReturn("openai-compatible");

        assertThat(router.resolve()).isSameAs(realClient);
        assertThat(router.provider()).isEqualTo("openai-compatible");
    }

    @Test
    @DisplayName("目标实现未装配 → 回退可用实现，且 provider() 汇报真实生效者（不谎报）")
    void shouldFallbackHonestlyWhenTargetMissing() {
        LlmClient mockClient = client("mock");
        LlmClientRouter router = router(Map.of("mock", mockClient));
        when(config.getString(ConfigKeys.LLM_PROVIDER, "mock")).thenReturn("openai-compatible");

        assertThat(router.resolve()).isSameAs(mockClient);
        assertThat(router.provider()).isEqualTo("mock");
        assertThat(router.availableProviders()).containsExactly("mock");
    }

    @Test
    @DisplayName("动态源缺失 → 回退静态 provider")
    void shouldFallbackToStaticProvider() {
        LlmClientRouter router = router(Map.of("mock", client("mock")), null);
        assertThat(router.configuredProvider()).isEqualTo("mock");
    }

    @Test
    @DisplayName("无任何实现 → 抛不可用异常，交编排器走降级矩阵")
    void shouldThrowWhenNoDelegate() {
        LlmClientRouter router = router(Map.of());
        when(config.getString(anyString(), anyString())).thenReturn("mock");

        assertThatThrownBy(router::resolve).isInstanceOf(LlmUnavailableException.class);
    }

    @Test
    @DisplayName("chat 转发至实际生效的实现")
    void shouldDelegateChat() {
        LlmClient mockClient = client("mock");
        LlmClientRouter router = router(Map.of("mock", mockClient));
        when(config.getString(ConfigKeys.LLM_PROVIDER, "mock")).thenReturn("mock");

        ChatResult expected = ChatResult.text("hi");
        when(mockClient.chat(org.mockito.ArgumentMatchers.any())).thenReturn(expected);

        assertThat(router.chat(ChatRequest.of("m", java.util.List.of(), java.util.List.of(),
                java.time.Duration.ofSeconds(1)))).isSameAs(expected);
    }

    private LlmClientRouter router(Map<String, LlmClient> delegates) {
        return new LlmClientRouter(delegates, config, PROPS);
    }

    private LlmClientRouter router(Map<String, LlmClient> delegates, DynamicConfigService cfg) {
        return new LlmClientRouter(delegates, cfg, PROPS);
    }

    private static LlmClient client(String provider) {
        LlmClient client = mock(LlmClient.class);
        when(client.provider()).thenReturn(provider);
        return client;
    }
}
