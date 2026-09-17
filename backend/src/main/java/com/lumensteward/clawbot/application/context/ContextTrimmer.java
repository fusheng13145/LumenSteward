package com.lumensteward.clawbot.application.context;

import com.lumensteward.clawbot.infrastructure.client.llm.dto.ChatMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 上下文裁剪器（架构 5.2 / SRS 9.4.4）。
 *
 * <p><b>主控变量是 token 而非轮数</b>（Q3 / 6.2.1 推导 2）：按固定轮数裁剪会导致超出模型窗口。
 *
 * <p><b>tool/assistant 配对完整性</b>（9.4.4 第 11-13 行，实践最易踩坑）：LLM 协议要求 {@code tool}
 * 消息必须紧跟其 {@code assistant} 调用；裁剪时若保留 tool 结果却丢弃其 assistant 调用，将导致模型
 * 报错。故本实现从最近向前保留，且把孤立的 tool 消息与其 assistant 调用作为<b>原子组</b>一并纳入，
 * 预算不足则整组丢弃。
 */
@Component
public class ContextTrimmer {

    private final TokenEstimator estimator;

    /**
     * 构造器注入（G-14）。
     *
     * @param estimator token 估算器
     */
    public ContextTrimmer(TokenEstimator estimator) {
        this.estimator = estimator;
    }

    /**
     * 裁剪历史（保留最近的消息，保持 tool/assistant 配对）。
     *
     * @param history              历史消息（不含 system 与当前 user）
     * @param budgetTokens         输入预算 token
     * @param reservedOutputTokens 预留输出 token
     * @return 裁剪后的消息（保持原顺序）
     */
    public List<ChatMessage> trim(List<ChatMessage> history, int budgetTokens, int reservedOutputTokens) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }
        int avail = budgetTokens - reservedOutputTokens;
        if (avail <= 0) {
            return List.of();
        }

        List<ChatMessage> kept = new ArrayList<>();
        Set<Integer> keptIndices = new HashSet<>();
        int used = 0;

        for (int i = history.size() - 1; i >= 0; i--) {
            if (keptIndices.contains(i)) {
                continue;
            }
            ChatMessage message = history.get(i);

            // 组装本条消息所属的原子组（tool 消息须与其 assistant 调用配对）
            List<Integer> groupIndices = new ArrayList<>();
            if (message.isTool()) {
                int callerIdx = findAssistantCaller(history, i, message.toolCallId());
                if (callerIdx < 0) {
                    // 真正的孤立 tool 消息：无对应 assistant 调用，直接丢弃（9.4.4 第 11-13 行）
                    continue;
                }
                groupIndices.add(callerIdx);   // assistant 在前
                groupIndices.add(i);           // tool 在后
            } else {
                groupIndices.add(i);
            }

            // 计算组内新增 token（跳过已保留项，避免重复计费）
            int groupTokens = 0;
            boolean alreadyFullyKept = true;
            for (int idx : groupIndices) {
                if (!keptIndices.contains(idx)) {
                    alreadyFullyKept = false;
                    groupTokens += estimator.estimate(history.get(idx));
                }
            }
            if (alreadyFullyKept) {
                continue;
            }
            if (used + groupTokens > avail) {
                // 预算不足：停止向前回溯（保留更近的整组）
                break;
            }

            // 组内按原顺序插到最前
            for (int k = groupIndices.size() - 1; k >= 0; k--) {
                int idx = groupIndices.get(k);
                if (!keptIndices.contains(idx)) {
                    kept.add(0, history.get(idx));
                    keptIndices.add(idx);
                }
            }
            used += groupTokens;
        }
        return kept;
    }

    /**
     * 从 {@code toolIndex} 向前查找产生指定 toolCallId 的 assistant 消息索引。
     *
     * @param history    历史
     * @param toolIndex  tool 消息索引
     * @param toolCallId 工具调用 id（可空）
     * @return assistant 索引；未找到返回 -1
     */
    private int findAssistantCaller(List<ChatMessage> history, int toolIndex, String toolCallId) {
        for (int j = toolIndex - 1; j >= 0; j--) {
            ChatMessage candidate = history.get(j);
            if (!candidate.isAssistant() || candidate.toolCalls() == null || candidate.toolCalls().isEmpty()) {
                continue;
            }
            if (toolCallId == null || toolCallId.isBlank()) {
                // 无 id 时，回退到最近的 assistant 工具调用消息
                return j;
            }
            boolean matched = candidate.toolCalls().stream()
                    .anyMatch(call -> toolCallId.equals(call.id()));
            if (matched) {
                return j;
            }
        }
        return -1;
    }
}
