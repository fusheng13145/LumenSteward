package com.lumensteward.clawbot.infrastructure.client.llm.script;

import com.lumensteward.clawbot.infrastructure.client.llm.dto.ChatMessage;
import com.lumensteward.clawbot.infrastructure.client.llm.dto.ChatResult;
import com.lumensteward.clawbot.infrastructure.client.llm.exception.LlmException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Mock 脚本的 token 计量可编程性测试（B-4 / 迭代 4 W5）。
 *
 * <p>Mock 此前恒回报 0 token，「计量在转」这一事实在本机无法取证；本测试锁定规则级
 * {@code usage} 的三条分支：显式声明优先、未声明回落脚本级 {@code defaultUsage}、
 * 节点格式非法同样回落且不抛出。
 */
class MockScriptTest {

    private final MockScript script = MockScript.fromResource("mock/llm-usage-cases.yml");

    private ChatResult replyTo(String userText) throws LlmException {
        return script.next(0, List.of(ChatMessage.user(userText)));
    }

    @Test
    @DisplayName("规则声明 usage：按其回报 prompt/completion/total")
    void shouldUseRuleLevelUsage() throws LlmException {
        ChatResult result = replyTo("请带计量地回复");

        assertThat(result.content()).isEqualTo("带计量的回复");
        assertThat(result.usage().promptTokens()).isEqualTo(640);
        assertThat(result.usage().completionTokens()).isEqualTo(40);
        assertThat(result.usage().totalTokens()).isEqualTo(680);
    }

    @Test
    @DisplayName("规则未声明 usage：回落脚本级 defaultUsage")
    void shouldFallbackToDefaultUsage() throws LlmException {
        ChatResult result = replyTo("跟随默认即可");

        assertThat(result.usage().promptTokens()).isEqualTo(500);
        assertThat(result.usage().completionTokens()).isEqualTo(50);
        assertThat(result.usage().totalTokens()).isEqualTo(550);
    }

    @Test
    @DisplayName("未命中任何规则：默认分支同样带 defaultUsage")
    void shouldCarryUsageOnDefaultBranch() throws LlmException {
        ChatResult result = replyTo("完全没命中的话");

        assertThat(result.content()).isEqualTo("默认回复");
        assertThat(result.usage().totalTokens()).isEqualTo(550);
    }

    @Test
    @DisplayName("usage 节点格式非法：回落默认且不抛异常（脚本容错，不阻断启动）")
    void shouldTolerateMalformedUsageNode() throws LlmException {
        assertThatCode(() -> replyTo("计量格式非法也没关系")).doesNotThrowAnyException();
        assertThat(replyTo("计量格式非法也没关系").usage().totalTokens()).isEqualTo(550);
    }

    @Test
    @DisplayName("空脚本/未配置 usage：沿用 EMPTY，不改变历史行为")
    void emptyScriptKeepsZeroUsage() throws LlmException {
        ChatResult result = MockScript.empty().next(0, List.of(ChatMessage.user("随便")));
        assertThat(result.usage().totalTokens()).isZero();
    }
}
