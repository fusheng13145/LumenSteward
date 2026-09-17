package com.lumensteward.clawbot.application.intent;

import com.fasterxml.jackson.databind.JsonNode;
import com.lumensteward.clawbot.common.util.JsonUtils;
import com.lumensteward.clawbot.domain.intent.IntentClassifier;
import com.lumensteward.clawbot.domain.intent.IntentResult;
import com.lumensteward.clawbot.domain.intent.IntentType;
import com.lumensteward.clawbot.infrastructure.client.llm.LlmClient;
import com.lumensteward.clawbot.infrastructure.client.llm.dto.ChatMessage;
import com.lumensteward.clawbot.infrastructure.client.llm.dto.ChatRequest;
import com.lumensteward.clawbot.infrastructure.client.llm.dto.ChatResult;
import com.lumensteward.clawbot.infrastructure.client.llm.exception.LlmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于 LLM 的意图分类器（架构 5.2 / SRS FR-05，Mock LLM 驱动）。
 *
 * <p>以受约束的 JSON 输出请模型给出意图、置信度与槽位；解析失败或调用异常时返回
 * {@link IntentResult#unknown()}（Fail-safe，由上层追问澄清，BR-08），不抛出中断链路。
 */
@Component
public class LlmIntentClassifier implements IntentClassifier {

    private static final Logger log = LoggerFactory.getLogger(LlmIntentClassifier.class);

    private static final String SYSTEM_PROMPT = """
            你是意图分类器。请判断用户消息的意图，并只输出 JSON：
            {"intent":"PET_PROFILE|EXPRESS|NAVIGATION|TTS|IMAGE|CHITCHAT|UNKNOWN",
             "confidence":0.0-1.0,"slots":{...}}
             slots 可包含 petName、trackingNo 等键。不要输出多余文字。""";

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final LlmClient llmClient;

    /**
     * 构造器注入（G-14）。
     *
     * @param llmClient LLM 客户端
     */
    public LlmIntentClassifier(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    @Override
    public IntentResult classify(List<ChatMessage> history, String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return IntentResult.unknown();
        }
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(SYSTEM_PROMPT));
        messages.add(ChatMessage.user(userMessage));
        try {
            ChatResult result = llmClient.chat(
                    ChatRequest.of(null, messages, List.of(), TIMEOUT));
            return parse(result.content());
        } catch (LlmException e) {
            log.warn("意图分类调用失败，回落 UNKNOWN: err={}", e.getMessage());
            return IntentResult.unknown();
        } catch (RuntimeException e) {
            log.warn("意图分类异常，回落 UNKNOWN: err={}", e.getMessage());
            return IntentResult.unknown();
        }
    }

    private IntentResult parse(String content) {
        JsonNode node = JsonUtils.readTree(content);
        if (node == null || !node.isObject()) {
            return IntentResult.unknown();
        }
        String intentText = node.hasNonNull("intent") ? node.get("intent").asText() : null;
        double confidence = node.hasNonNull("confidence") ? node.get("confidence").asDouble(0.0) : 0.0;
        IntentType intent = parseIntent(intentText);
        Map<String, Object> slots = new LinkedHashMap<>();
        JsonNode slotsNode = node.get("slots");
        if (slotsNode != null && slotsNode.isObject()) {
            slotsNode.fields().forEachRemaining(e -> slots.put(e.getKey(), e.getValue().asText()));
        }
        return new IntentResult(intent, confidence, slots);
    }

    private static IntentType parseIntent(String text) {
        if (text == null || text.isBlank()) {
            return IntentType.UNKNOWN;
        }
        try {
            return IntentType.valueOf(text.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return IntentType.UNKNOWN;
        }
    }
}
