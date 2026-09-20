package com.lumensteward.clawbot.application.memory;

import com.lumensteward.clawbot.application.config.ConfigKeys;
import com.lumensteward.clawbot.application.config.DynamicConfigService;
import com.lumensteward.clawbot.domain.memory.MemoryStore;
import com.lumensteward.clawbot.domain.memory.MemoryWrite;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 状态库生长监听器单测（迭代 4 W6）。
 *
 * <p>生长发生在<b>另一线程</b>（用户可感知的回复不得被额外一次模型调用拖慢），
 * 故断言统一使用 Mockito {@code timeout(...)} 观察异步结果，而非 sleep。
 *
 * <p>三条硬约束逐条验证：默认关闭（成本闸门）、写库异常不外溢、后台线程不因异常而死。
 */
class MemoryGrowthListenerTest {

    private final MemoryExtractor extractor = mock(MemoryExtractor.class);
    private final MemoryStore memoryStore = mock(MemoryStore.class);
    private final DynamicConfigService config = mock(DynamicConfigService.class);
    private final MemoryGrowthListener listener =
            new MemoryGrowthListener(extractor, memoryStore, config);

    @AfterEach
    void tearDown() {
        listener.shutdown();
    }

    private void enableGrowth() {
        when(config.getBoolean(ConfigKeys.MEMORY_GROWTH_ENABLED, false)).thenReturn(true);
        when(config.getInt(ConfigKeys.MEMORY_GROWTH_MAX_ITEMS, 5)).thenReturn(3);
    }

    private static MemoryGrowthNotice notice(String userMessage) {
        return new MemoryGrowthNotice("openid-1", 7L, "trace-1", userMessage, "好的，记下了");
    }

    private static MemoryWrite candidate(String name) {
        return new MemoryWrite("openid-1", null, name, "内容", null, null,
                BigDecimal.ONE, null, null);
    }

    @Test
    @DisplayName("开关默认关闭（预留未启用）：不查配置以外的任何成本，不调抽取器")
    void growthDisabledByDefault() {
        when(config.getBoolean(ConfigKeys.MEMORY_GROWTH_ENABLED, false)).thenReturn(false);

        listener.onConversationTurn(notice("我妈住在北京"));

        verify(extractor, never()).extract(any(), anyInt());
        assertThat(listener.droppedCount()).isZero();
    }

    @Test
    @DisplayName("开启后：按配置上限异步抽取，并把每条候选交给 upsert")
    void growsWhenEnabled() {
        enableGrowth();
        when(extractor.extract(any(), eq(3))).thenReturn(List.of(candidate("妈妈"), candidate("老家")));
        when(memoryStore.upsert(any())).thenReturn(MemoryStore.WriteOutcome.CREATED);

        listener.onConversationTurn(notice("我妈住在北京"));

        verify(memoryStore, timeout(3000).times(2)).upsert(any());
        // 上限来自运行时配置（改值免重启，FR-18 口径）
        verify(extractor, timeout(3000)).extract(any(), eq(3));
    }

    @Test
    @DisplayName("降级/空回复的活动不送抽取（即便开关已开）")
    void skipsWorthlessNotice() {
        enableGrowth();

        listener.onConversationTurn(new MemoryGrowthNotice("openid-1", 7L, "t", "在吗", null));
        listener.onConversationTurn(null);

        verify(extractor, after(500).never()).extract(any(), anyInt());
    }

    @Test
    @DisplayName("写库异常被吞掉且后台线程仍存活：后续轮次照常生长")
    void survivesStoreFailureAndKeepsWorking() {
        enableGrowth();
        when(extractor.extract(any(), anyInt())).thenReturn(List.of(candidate("妈妈")));
        when(memoryStore.upsert(any()))
                .thenThrow(new IllegalStateException("db down"))
                .thenReturn(MemoryStore.WriteOutcome.REINFORCED);

        assertThatCode(() -> listener.onConversationTurn(notice("第一轮"))).doesNotThrowAnyException();
        verify(memoryStore, timeout(3000)).upsert(any());

        listener.onConversationTurn(notice("第二轮"));
        verify(memoryStore, timeout(3000).atLeast(2)).upsert(any());
    }
}
