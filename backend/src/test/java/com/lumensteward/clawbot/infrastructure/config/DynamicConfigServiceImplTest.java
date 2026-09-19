package com.lumensteward.clawbot.infrastructure.config;

import com.lumensteward.clawbot.application.config.ConfigKeys;
import com.lumensteward.clawbot.application.config.DynamicConfigService;
import com.lumensteward.clawbot.infrastructure.cache.ConfigCacheService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 动态配置源单测（FR-18 / T10）。
 *
 * <p>核心契约：配置缺失、空值、格式非法、缓存不可用，一律回退兜底值并告警，<b>绝不抛异常</b>——
 * 保证"改错一个配置项"不会击穿对话链路。
 */
class DynamicConfigServiceImplTest {

    private final ConfigCacheService cache = mock(ConfigCacheService.class);
    private final DynamicConfigService service = new DynamicConfigServiceImpl(cache);

    @Test
    @DisplayName("配置缺失 → 回退兜底值（不抛异常）")
    void shouldFallbackWhenMissing() {
        when(cache.get(ConfigKeys.LLM_MODEL)).thenReturn(null);
        assertThat(service.getString(ConfigKeys.LLM_MODEL, "mock-model")).isEqualTo("mock-model");
        assertThat(service.getInt("orchestration.max-rounds", 5)).isEqualTo(5);
        assertThat(service.getBoolean("safety.strict-mode", false)).isFalse();
    }

    @Test
    @DisplayName("空值 → 回退兜底值")
    void shouldFallbackWhenBlank() {
        when(cache.get(ConfigKeys.LLM_MODEL)).thenReturn("   ");
        assertThat(service.getString(ConfigKeys.LLM_MODEL, "mock-model")).isEqualTo("mock-model");
    }

    @Test
    @DisplayName("整数解析失败 → 回退兜底值")
    void shouldFallbackWhenNotInteger() {
        when(cache.get("orchestration.max-rounds")).thenReturn("abc");
        assertThat(service.getInt("orchestration.max-rounds", 5)).isEqualTo(5);
    }

    @Test
    @DisplayName("布尔支持 true/1/yes/on 与 false/0/no/off；非法值回退")
    void shouldParseBooleanVariants() {
        when(cache.get("safety.fail-closed")).thenReturn("YES");
        assertThat(service.getBoolean("safety.fail-closed", false)).isTrue();
        when(cache.get("safety.fail-closed")).thenReturn("off");
        assertThat(service.getBoolean("safety.fail-closed", true)).isFalse();
        when(cache.get("safety.fail-closed")).thenReturn("maybe");
        assertThat(service.getBoolean("safety.fail-closed", true)).isTrue();
    }

    @Test
    @DisplayName("工具开关：JSON 数组与逗号分隔两种写法均可解析")
    void shouldParseListBothForms() {
        when(cache.get(ConfigKeys.ORCHESTRATION_DISABLED_TOOLS)).thenReturn("[\"plan_route\",\"query_express\"]");
        assertThat(service.getList(ConfigKeys.ORCHESTRATION_DISABLED_TOOLS, List.of()))
                .containsExactly("plan_route", "query_express");
        when(cache.get(ConfigKeys.ORCHESTRATION_DISABLED_TOOLS)).thenReturn("plan_route, query_express");
        assertThat(service.getSet(ConfigKeys.ORCHESTRATION_DISABLED_TOOLS, Set.of()))
                .containsExactlyInAnyOrder("plan_route", "query_express");
    }

    @Test
    @DisplayName("空数组 [] → 回退兜底值（区分「未配置」与「显式空」由调用方决定）")
    void shouldFallbackWhenEmptyArray() {
        when(cache.get(ConfigKeys.ORCHESTRATION_DISABLED_TOOLS)).thenReturn("[]");
        assertThat(service.getList(ConfigKeys.ORCHESTRATION_DISABLED_TOOLS, List.of("x")))
                .containsExactly("x");
    }

    @Test
    @DisplayName("缓存读取抛异常 → 回退兜底值（降级不阻断）")
    void shouldFallbackWhenCacheThrows() {
        when(cache.get(ConfigKeys.LLM_MODEL)).thenThrow(new IllegalStateException("redis down"));
        assertThat(service.getString(ConfigKeys.LLM_MODEL, "mock-model")).isEqualTo("mock-model");
    }

    @Test
    @DisplayName("可用性探测：探针键可读即视为可用")
    void shouldProbeAvailability() {
        when(cache.get(ConfigKeys.LLM_MODEL)).thenReturn("mock-model");
        assertThat(service.isAvailable()).isTrue();
        when(cache.get(ConfigKeys.LLM_MODEL)).thenReturn(null);
        assertThat(service.isAvailable()).isFalse();
    }

    @Test
    @DisplayName("键为空 → 返回 null，不查询缓存")
    void shouldReturnNullWhenKeyBlank() {
        assertThat(service.get("  ")).isNull();
    }
}
