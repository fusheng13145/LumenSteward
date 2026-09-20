package com.lumensteward.clawbot.application.memory;

import com.lumensteward.clawbot.application.ratelimit.CostBudgetService;
import com.lumensteward.clawbot.common.enums.MemoryKind;
import com.lumensteward.clawbot.common.enums.MemoryOrigin;
import com.lumensteward.clawbot.domain.memory.MemoryWrite;
import com.lumensteward.clawbot.infrastructure.client.llm.LlmClient;
import com.lumensteward.clawbot.infrastructure.client.llm.dto.ChatRequest;
import com.lumensteward.clawbot.infrastructure.client.llm.dto.ChatResult;
import com.lumensteward.clawbot.infrastructure.client.llm.dto.TokenUsage;
import com.lumensteward.clawbot.infrastructure.client.llm.exception.LlmTimeoutException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LLM 记忆抽取器单测（迭代 4 W6 生长管道的「抽取」环节）。
 *
 * <p>关注三件事：① 模型输出的解析容错（对象/裸数组/围栏/脏 kind/文本置信度）；
 * ② <b>永不抛出</b>——模型失败只是「这条没记住」；③ 抽取消耗必须计入 FR-20 ③ 日预算，
 * 不隐藏这笔额外成本。
 */
class LlmMemoryExtractorTest {

    private final LlmClient llm = mock(LlmClient.class);
    private final CostBudgetService costBudgetService = mock(CostBudgetService.class);
    private final LlmMemoryExtractor extractor = new LlmMemoryExtractor(llm, costBudgetService);

    private static MemoryGrowthNotice notice() {
        return new MemoryGrowthNotice("openid-1", 42L, "trace-1", "我妈住在北京，爱喝绿茶", "记下了～");
    }

    private void reply(String content) {
        when(llm.chat(any())).thenReturn(new ChatResult(content, List.of(),
                TokenUsage.of(100, 40), "stop", null));
    }

    @Test
    @DisplayName("对象包裹格式：解析出条目并带上溯源（会话/链路/抽取器/来源方式）")
    void parsesObjectWrappedOutput() {
        reply("{\"items\":[{\"kind\":\"PERSON\",\"name\":\"妈妈\",\"content\":\"住在北京，喜欢喝绿茶\",\"confidence\":0.8}]}");

        List<MemoryWrite> writes = extractor.extract(notice(), 5);

        assertThat(writes).hasSize(1);
        MemoryWrite write = writes.get(0);
        assertThat(write.openid()).isEqualTo("openid-1");
        assertThat(write.kind()).isEqualTo(MemoryKind.PERSON);
        assertThat(write.name()).isEqualTo("妈妈");
        assertThat(write.content()).isEqualTo("住在北京，喜欢喝绿茶");
        assertThat(write.origin()).isEqualTo(MemoryOrigin.AUTO_EXTRACT);
        assertThat(write.extractor()).isEqualTo(LlmMemoryExtractor.EXTRACTOR_ID);
        assertThat(write.sourceSessionId()).isEqualTo(42L);
        assertThat(write.sourceTraceId()).isEqualTo("trace-1");
    }

    @Test
    @DisplayName("裸数组 + ```json 围栏 + 前后解释文字：仍取到 JSON 主体")
    void toleratesFencedAndProseyOutput() {
        reply("好的，以下是抽取结果：\n```json\n[{\"kind\":\"HABIT\",\"name\":\"晨跑\",\"content\":\"每周三晨跑\"}]\n```\n希望有帮助");

        assertThat(extractor.extract(notice(), 5)).singleElement()
                .satisfies(write -> {
                    assertThat(write.kind()).isEqualTo(MemoryKind.HABIT);
                    assertThat(write.confidence()).isNull();
                });
    }

    @Test
    @DisplayName("脏值处理：无法识别的 kind 回落 FACT，文本置信度置空，缺名/缺正文的条目被丢弃")
    void degradesDirtyCandidates() {
        reply("{\"items\":["
                + "{\"kind\":\"宠物\",\"name\":\"咪咪\",\"content\":\"三花猫\",\"confidence\":\"很高\"},"
                + "{\"kind\":\"FACT\",\"name\":\"\",\"content\":\"空名应被丢弃\"},"
                + "{\"kind\":\"FACT\",\"name\":\"有名无正文\"}"
                + "]}");

        List<MemoryWrite> writes = extractor.extract(notice(), 5);

        assertThat(writes).hasSize(1);
        assertThat(writes.get(0).kind()).isEqualTo(MemoryKind.FACT);
        assertThat(writes.get(0).confidence()).isNull();
    }

    @Test
    @DisplayName("条数上限：配置值生效，且被硬上限 10 夹紧")
    void capsCandidateCount() {
        StringBuilder items = new StringBuilder("{\"items\":[");
        for (int i = 0; i < 20; i++) {
            items.append(i == 0 ? "" : ",")
                    .append("{\"kind\":\"FACT\",\"name\":\"k").append(i).append("\",\"content\":\"v\"}");
        }
        reply(items + "]}");

        assertThat(extractor.extract(notice(), 2)).hasSize(2);
        assertThat(extractor.extract(notice(), 999)).hasSize(10);
    }

    @Test
    @DisplayName("非 JSON / 空输出 / 上限非法 → 空列表（宁可不记，也不写脏数据）")
    void returnsEmptyOnUnusableOutput() {
        reply("我不确定有什么值得记的。");
        assertThat(extractor.extract(notice(), 5)).isEmpty();

        reply("");
        assertThat(extractor.extract(notice(), 5)).isEmpty();

        reply("[{\"kind\":\"FACT\",\"name\":\"k\",\"content\":\"v\"}]");
        assertThat(extractor.extract(notice(), 0)).isEmpty();
    }

    @Test
    @DisplayName("模型调用失败：吞掉异常返回空列表，绝不回流主链路")
    void swallowsLlmFailure() {
        when(llm.chat(any())).thenThrow(new LlmTimeoutException("timeout"));

        assertThatCode(() -> extractor.extract(notice(), 5)).doesNotThrowAnyException();
        assertThat(extractor.extract(notice(), 5)).isEmpty();
    }

    @Test
    @DisplayName("额外一次模型调用的 token 计入日预算（不隐藏成本）")
    void recordsTokenUsage() {
        reply("[]");

        extractor.extract(notice(), 5);

        verify(costBudgetService).recordLlmCall(140);
    }

    @Test
    @DisplayName("降级回复（无终态文本）不送抽取，省掉一次模型调用")
    void skipsWorthlessNotice() {
        assertThat(extractor.extract(new MemoryGrowthNotice("openid-1", 1L, "t", "在吗", " "), 5))
                .isEmpty();
        assertThat(extractor.extract(null, 5)).isEmpty();
        verify(llm, never()).chat(any());
    }

    @Test
    @DisplayName("载荷以唯一标记开头且置于用户原文之前（Mock 脚本可靠命中，见 llm-scripts.yml）")
    void payloadCarriesMarker() {
        reply("[]");
        ArgumentCaptor<ChatRequest> captor = ArgumentCaptor.forClass(ChatRequest.class);

        extractor.extract(notice(), 5);

        verify(llm).chat(captor.capture());
        String userPayload = captor.getValue().messages().stream()
                .filter(message -> "user".equals(message.role()))
                .findFirst().orElseThrow().content();
        assertThat(userPayload).startsWith(LlmMemoryExtractor.PAYLOAD_MARKER);
        assertThat(userPayload).contains("我妈住在北京");
    }

    @Test
    @DisplayName("costBudgetService 为 null（standalone 构造）时不抛异常")
    void worksWithoutCostBudget() {
        reply("[{\"kind\":\"FACT\",\"name\":\"k\",\"content\":\"v\"}]");
        LlmMemoryExtractor standalone = new LlmMemoryExtractor(llm, null);

        assertThat(standalone.extract(notice(), 5)).hasSize(1);
        verify(costBudgetService, never()).recordLlmCall(anyInt());
    }
}
