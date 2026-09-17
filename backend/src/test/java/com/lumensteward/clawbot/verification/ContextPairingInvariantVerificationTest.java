package com.lumensteward.clawbot.verification;

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
 * 独立验证：上下文裁剪保持 tool/assistant 配对不变式（SRS 9.4.4 第 11-13 行）。
 *
 * <p>若裁剪后保留 {@code tool} 消息却丢弃其 {@code assistant} 调用，真实 LLM 会直接报错。
 * 本测试在多档预算下逐一验证不变式（不复用工程师测试类）。
 */
class ContextPairingInvariantVerificationTest {

    private final ContextTrimmer trimmer = new ContextTrimmer(new HeuristicTokenEstimator());

    private static ChatMessage assistantCall(String... callIds) {
        List<ToolCall> calls = new ArrayList<>();
        for (String id : callIds) {
            calls.add(new ToolCall(id, "function", "manage_pet_profile", "{}"));
        }
        return ChatMessage.assistantToolCalls(calls);
    }

    /** 构造含两轮工具调用的历史（每轮含 assistant 调用 + 配对 tool 结果）。 */
    private static List<ChatMessage> buildHistory() {
        List<ChatMessage> history = new ArrayList<>();
        history.add(ChatMessage.user("帮我登记一下我的猫叫咪咪"));
        history.add(assistantCall("c1"));
        history.add(ChatMessage.tool("c1", "manage_pet_profile", "{\"status\":\"SUCCESS\"}"));
        history.add(ChatMessage.assistant("好嘞，咪咪的档案我已经记下啦。"));
        history.add(ChatMessage.user("它几岁了"));
        history.add(assistantCall("c2"));
        history.add(ChatMessage.tool("c2", "manage_pet_profile", "{\"age\":3}"));
        history.add(ChatMessage.assistant("咪咪今年 3 岁。"));
        return history;
    }

    /** 不变式：任一保留的 tool 消息，其 toolCallId 必须已被更早保留的 assistant 消息声明。 */
    private static void assertPairingInvariant(List<ChatMessage> kept) {
        List<String> declared = new ArrayList<>();
        for (ChatMessage message : kept) {
            if (message.isAssistant() && message.toolCalls() != null) {
                for (ToolCall call : message.toolCalls()) {
                    declared.add(call.id());
                }
            }
            if (message.isTool()) {
                assertThat(declared)
                        .as("保留的 tool 消息 %s 必须有其 assistant 调用配对", message.toolCallId())
                        .contains(message.toolCallId());
            }
        }
    }

    @Test
    @DisplayName("预算充足：全量保留，配对不变式成立")
    void largeBudgetKeepsAll() {
        List<ChatMessage> history = buildHistory();
        List<ChatMessage> kept = trimmer.trim(history, 8000, 1000);

        assertThat(kept).hasSize(history.size());
        assertPairingInvariant(kept);
    }

    @Test
    @DisplayName("多档预算（0..40）：裁剪后配对不变式始终成立")
    void pairingInvariantHoldsAcrossBudgets() {
        List<ChatMessage> history = buildHistory();
        for (int budget = 0; budget <= 40; budget++) {
            List<ChatMessage> kept = trimmer.trim(history, budget, 0);
            assertPairingInvariant(kept);
            // 保留顺序必须与原顺序一致（子序列）
            List<ChatMessage> ordered = new ArrayList<>(kept);
            assertThat(subsequenceOf(ordered, history)).as("budget=%d 顺序必须保持", budget).isTrue();
        }
    }

    @Test
    @DisplayName("预算为 0 / 负：返回空列表（不抛异常）")
    void zeroBudgetReturnsEmpty() {
        assertThat(trimmer.trim(buildHistory(), 0, 0)).isEmpty();
        assertThat(trimmer.trim(buildHistory(), 10, 100)).isEmpty();
    }

    @Test
    @DisplayName("真正的孤立 tool 消息（无 assistant 调用）被安全丢弃")
    void orphanToolDropped() {
        List<ChatMessage> history = List.of(
                ChatMessage.user("hi"),
                ChatMessage.tool("orphan", "manage_pet_profile", "{}"));
        List<ChatMessage> kept = trimmer.trim(history, 8000, 0);

        assertPairingInvariant(kept);
        assertThat(kept).noneMatch(ChatMessage::isTool);
    }

    private static boolean subsequenceOf(List<ChatMessage> sub, List<ChatMessage> full) {
        int i = 0;
        for (ChatMessage message : full) {
            if (i < sub.size() && sub.get(i) == message) {
                i++;
            }
        }
        return i == sub.size();
    }
}
