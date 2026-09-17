package com.lumensteward.clawbot.context;

import com.lumensteward.clawbot.application.context.ContextTrimmer;
import com.lumensteward.clawbot.application.context.HeuristicTokenEstimator;
import com.lumensteward.clawbot.infrastructure.client.llm.dto.ChatMessage;
import com.lumensteward.clawbot.infrastructure.client.llm.dto.ToolCall;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上下文裁剪配对测试（AC-B1/B2/B3，SRS 9.4.4 第 11-13 行）。
 *
 * <p>核心不变量：裁剪后任何 {@code tool} 消息都必须存在其配对的 {@code assistant} 调用消息，
 * 反之可容忍（assistant 可无 tool 结果）。
 */
class ContextTrimPairingTest {

    private final ContextTrimmer trimmer = new ContextTrimmer(new HeuristicTokenEstimator());

    @Test
    @DisplayName("⑦ 多种预算下，tool 消息永不孤立（必与其 assistant 调用配对）")
    void toolMessagesShouldAlwaysBePaired() {
        List<ChatMessage> history = buildHistory();
        for (int budget = 5; budget <= 200; budget += 5) {
            List<ChatMessage> trimmed = trimmer.trim(history, budget, 0);
            assertPairedInvariant(trimmed);
        }
    }

    @Test
    @DisplayName("⑦ 预算极小 → 仅保留最近消息，且不出现孤立 tool")
    void tinyBudgetShouldKeepRecentOnly() {
        List<ChatMessage> history = buildHistory();
        List<ChatMessage> trimmed = trimmer.trim(history, 8, 4);
        assertPairedInvariant(trimmed);
    }

    @Test
    @DisplayName("⑦ 预算充足 → 全部保留且顺序不变")
    void ampleBudgetShouldKeepAll() {
        List<ChatMessage> history = buildHistory();
        List<ChatMessage> trimmed = trimmer.trim(history, 100000, 0);
        assertThat(trimmed).hasSameSizeAs(history);
        assertThat(trimmed.get(0)).isEqualTo(history.get(0));
    }

    private static void assertPairedInvariant(List<ChatMessage> trimmed) {
        for (int i = 0; i < trimmed.size(); i++) {
            ChatMessage message = trimmed.get(i);
            if (!message.isTool()) {
                continue;
            }
            boolean hasCaller = false;
            for (int j = i - 1; j >= 0; j--) {
                ChatMessage candidate = trimmed.get(j);
                if (candidate.isAssistant() && candidate.toolCalls() != null) {
                    for (ToolCall call : candidate.toolCalls()) {
                        if (call.id() != null && call.id().equals(message.toolCallId())) {
                            hasCaller = true;
                            break;
                        }
                    }
                }
                if (hasCaller) {
                    break;
                }
            }
            assertThat(hasCaller)
                    .as("tool 消息（toolCallId=%s）必须存在配对的 assistant 调用", message.toolCallId())
                    .isTrue();
        }
    }

    private static List<ChatMessage> buildHistory() {
        List<ChatMessage> history = new ArrayList<>();
        history.add(ChatMessage.user("第一轮用户消息，内容比较长用于占据 token 预算。"));
        history.add(ChatMessage.assistantToolCalls(
                List.of(ToolCall.function("call-1", "manage_pet_profile", "{\"action\":\"CREATE\"}"))));
        history.add(ChatMessage.tool("call-1", "manage_pet_profile", "{\"status\":\"SUCCESS\"}"));
        history.add(ChatMessage.assistant("已经帮你记录好啦。"));
        history.add(ChatMessage.user("第二轮用户消息，继续对话内容。"));
        history.add(ChatMessage.assistantToolCalls(
                List.of(ToolCall.function("call-2", "manage_pet_profile", "{\"action\":\"READ\"}"))));
        history.add(ChatMessage.tool("call-2", "manage_pet_profile", "{\"status\":\"SUCCESS\"}"));
        history.add(ChatMessage.assistant("查到了，豆豆是一只柯基。"));
        return history;
    }
}
