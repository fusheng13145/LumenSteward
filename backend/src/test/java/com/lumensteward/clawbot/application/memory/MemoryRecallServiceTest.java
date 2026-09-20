package com.lumensteward.clawbot.application.memory;

import com.lumensteward.clawbot.application.config.ConfigKeys;
import com.lumensteward.clawbot.application.config.DynamicConfigService;
import com.lumensteward.clawbot.common.enums.MemoryKind;
import com.lumensteward.clawbot.common.enums.MemoryOrigin;
import com.lumensteward.clawbot.common.enums.MemoryStatus;
import com.lumensteward.clawbot.domain.memory.MemoryFact;
import com.lumensteward.clawbot.domain.memory.MemoryStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 状态库召回注入单测（迭代 4 W6）。
 *
 * <p>召回块直接改变送给模型的上下文，因此它的<b>自我限流</b>（条数 + 字符双闸）与
 * <b>fail-open</b>（读不到就当没有，绝不让本轮回复失败）同样重要。
 */
class MemoryRecallServiceTest {

    private final MemoryStore memoryStore = mock(MemoryStore.class);
    private final DynamicConfigService config = mock(DynamicConfigService.class);
    private final MemoryRecallService service = new MemoryRecallService(memoryStore, config);

    private void enable() {
        when(config.getBoolean(ConfigKeys.MEMORY_RECALL_ENABLED, true)).thenReturn(true);
        when(config.getInt(ConfigKeys.MEMORY_RECALL_MAX_ITEMS, 8)).thenReturn(8);
        when(config.getInt(ConfigKeys.MEMORY_RECALL_MAX_CHARS, 600)).thenReturn(600);
    }

    private static MemoryFact fact(MemoryKind kind, String name, String content) {
        LocalDateTime now = LocalDateTime.now();
        return new MemoryFact(1L, "openid-1", kind, name, content, MemoryOrigin.AUTO_EXTRACT,
                "llm-extract-v1", null, MemoryStatus.ACTIVE, null, 7L, "trace-1", 2, now, now);
    }

    @Test
    @DisplayName("开关关闭：不查库、不注入")
    void disabledSkipsQuery() {
        when(config.getBoolean(ConfigKeys.MEMORY_RECALL_ENABLED, true)).thenReturn(false);

        assertThat(service.buildRecallBlock("openid-1")).isNull();
        verify(memoryStore, never()).recallActive(anyString(), anyInt());
    }

    @Test
    @DisplayName("openid 空白：不查库（BR-07 无归属即不读）")
    void blankOpenidSkipsQuery() {
        assertThat(service.buildRecallBlock(" ")).isNull();
        verify(memoryStore, never()).recallActive(anyString(), anyInt());
    }

    @Test
    @DisplayName("有条目：带用途说明的 system 块，逐条列出类型/名称/正文")
    void rendersFactsWithHeader() {
        enable();
        when(memoryStore.recallActive(anyString(), anyInt())).thenReturn(List.of(
                fact(MemoryKind.PERSON, "妈妈", "住在北京，喜欢喝绿茶"),
                fact(MemoryKind.HABIT, "晨跑", "每周三早上晨跑")));

        String block = service.buildRecallBlock("openid-1");

        assertThat(block).contains("跨会话记忆")
                .contains("不得据此编造")
                .contains("[人物] 妈妈：住在北京，喜欢喝绿茶")
                .contains("[惯例] 晨跑：每周三早上晨跑");
    }

    @Test
    @DisplayName("字符上限：超限的条目不再追加（防挤占当轮上下文）")
    void capsTotalChars() {
        enable();
        when(memoryStore.recallActive(anyString(), anyInt())).thenReturn(List.of(
                fact(MemoryKind.FACT, "a", "x".repeat(40)),
                fact(MemoryKind.FACT, "b", "y".repeat(40)),
                fact(MemoryKind.FACT, "c", "z".repeat(40))));

        String block = MemoryRecallService.render(
                memoryStore.recallActive("openid-1", 8), 100);

        assertThat(block).isNotNull();
        assertThat(block).contains("[事实] a：").doesNotContain("[事实] c：");
        // 上限只约束条目正文累计长度（块头固定开销之外），此处验证未把三条都塞进去
        assertThat(factsCount(block)).isEqualTo(2);
    }

    private static long factsCount(String block) {
        return block.lines().filter(line -> line.startsWith("- [")).count();
    }

    @Test
    @DisplayName("读库异常 → fail-open 返回 null，本轮按无长期记忆继续")
    void failsOpenOnStoreError() {
        enable();
        when(memoryStore.recallActive(anyString(), anyInt()))
                .thenThrow(new IllegalStateException("db down"));

        assertThat(service.buildRecallBlock("openid-1")).isNull();
    }

    @Test
    @DisplayName("无条目 / 上限非正：不产出空块（省一次无意义的 system 消息）")
    void emptyOrNonPositiveLimitsProduceNull() {
        enable();
        when(memoryStore.recallActive(anyString(), anyInt())).thenReturn(List.of());
        assertThat(service.buildRecallBlock("openid-1")).isNull();

        List<MemoryFact> facts = new ArrayList<>(List.of(fact(MemoryKind.FACT, "k", "v")));
        assertThat(MemoryRecallService.render(facts, 0)).isNull();
        assertThat(MemoryRecallService.render(List.of(), 600)).isNull();
        assertThat(MemoryRecallService.render(null, 600)).isNull();
    }
}
