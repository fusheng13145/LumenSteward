package com.lumensteward.clawbot.application.memory;

import com.lumensteward.clawbot.domain.memory.MemoryWrite;
import com.lumensteward.clawbot.infrastructure.client.llm.MockLlmClient;
import com.lumensteward.clawbot.infrastructure.client.llm.dto.ChatMessage;
import com.lumensteward.clawbot.infrastructure.client.llm.dto.ChatRequest;
import com.lumensteward.clawbot.infrastructure.client.llm.dto.ChatResult;
import com.lumensteward.clawbot.infrastructure.client.llm.script.MockScript;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 生长管道与 <b>真实 Mock 脚本</b> 的联调测试（迭代 4 W6，离线可跑）。
 *
 * <p>与前面用桩的测试不同，这里加载 classpath 上真实的 {@code mock/llm-scripts.yml}，
 * 因此它同时守住两个只有合起来才会暴露的坑：
 * <ol>
 *   <li>抽取请求的载荷里<b>内嵌用户原文</b>——若脚本规则顺序不对，用户说「我妈<b>叫</b>…」
 *       就会被宠物建档规则抢先命中，抽取拿到的是 {@code tool_calls} 而不是 JSON；</li>
 *   <li>脚本内容必须真的能被 {@link LlmMemoryExtractor} 解析，否则离线 e2e 的「生长」是假的。</li>
 * </ol>
 * 这也是本地 {@code java -jar} 冷启动能演示状态库生长（{@code llm.provider=mock}）的前提。
 */
class MemoryGrowthMockScriptTest {

    /** 与生产装配同源：加载 classpath 上的 mock/llm-scripts.yml。 */
    private final MockLlmClient llm = new MockLlmClient(MockScript.fromResource("mock/llm-scripts.yml"));
    private final LlmMemoryExtractor extractor = new LlmMemoryExtractor(llm, null);

    @Test
    @DisplayName("抽取载荷命中 memory-extract 规则：即使用户原文含「叫」也不被建档规则抢走")
    void extractionPayloadWinsOverConversationRules() throws Exception {
        String payload = LlmMemoryExtractor.PAYLOAD_MARKER + "\n用户：我妈住在北京，她叫王秀兰\n管家：记下了";
        ChatResult result = llm.chat(ChatRequest.of(null,
                List.of(ChatMessage.system("抽取器指令"), ChatMessage.user(payload)),
                List.of(), null));

        assertThat(result.hasToolCalls()).isFalse();
        assertThat(result.content()).contains("\"items\"");
    }

    @Test
    @DisplayName("脚本输出可被抽取器解析为带溯源候选（离线 e2e 生长的数据面成立）")
    void scriptOutputParsesIntoCandidates() {
        List<MemoryWrite> writes = extractor.extract(
                new MemoryGrowthNotice("openid-mock", 9L, "trace-mock",
                        "我妈住在北京，平时爱喝绿茶", "好嘞，我记住了"), 5);

        assertThat(writes).hasSize(2);
        assertThat(writes).allSatisfy(write -> {
            assertThat(write.openid()).isEqualTo("openid-mock");
            assertThat(write.sourceSessionId()).isEqualTo(9L);
            assertThat(write.sourceTraceId()).isEqualTo("trace-mock");
            assertThat(write.extractor()).isEqualTo(LlmMemoryExtractor.EXTRACTOR_ID);
        });
        assertThat(writes.get(0).kind().name()).isEqualTo("PERSON");
        assertThat(writes.get(0).confidence()).isNotNull();
    }

    @Test
    @DisplayName("主链路对话不受新增规则影响：普通提问仍走默认回复")
    void ordinaryConversationStillUsesDefault() throws Exception {
        ChatResult result = llm.chat(ChatRequest.of(null,
                List.of(ChatMessage.system("s"), ChatMessage.user("今天天气怎么样")),
                List.of(), null));

        assertThat(result.content()).startsWith("（Mock）");
        assertThat(result.hasToolCalls()).isFalse();
    }
}
