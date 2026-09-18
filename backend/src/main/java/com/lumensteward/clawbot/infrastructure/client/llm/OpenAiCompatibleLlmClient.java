package com.lumensteward.clawbot.infrastructure.client.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lumensteward.clawbot.common.util.JsonUtils;
import com.lumensteward.clawbot.domain.port.model.VisionRequest;
import com.lumensteward.clawbot.domain.port.model.VisionResult;
import com.lumensteward.clawbot.infrastructure.client.llm.dto.ChatMessage;
import com.lumensteward.clawbot.infrastructure.client.llm.dto.ChatRequest;
import com.lumensteward.clawbot.infrastructure.client.llm.dto.ChatResult;
import com.lumensteward.clawbot.infrastructure.client.llm.dto.TokenUsage;
import com.lumensteward.clawbot.infrastructure.client.llm.dto.ToolCall;
import com.lumensteward.clawbot.infrastructure.client.llm.exception.LlmException;
import com.lumensteward.clawbot.infrastructure.client.llm.exception.LlmProtocolException;
import com.lumensteward.clawbot.infrastructure.client.llm.exception.LlmTimeoutException;
import com.lumensteward.clawbot.infrastructure.client.llm.exception.LlmUnavailableException;
import com.lumensteward.clawbot.infrastructure.config.properties.LlmProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * OpenAI 兼容协议的 LLM 客户端（架构 5.1 / SRS 9.4.1，8.1.2）。
 *
 * <p>当 {@code llm.provider} 为 {@code openai-compatible}（或别名 {@code real}）时装配，覆盖多数
 * 国内大模型厂商；统一超时（文本 15s / 视觉 20s）由共享 {@link RestClient} 实施，熔断由 Resilience4j
 * 在调用外层保护（NFR-RE-03）。
 *
 * <p>失败路径统一转译为本系统自有异常（G-12：不原样透传第三方返回码）。
 */
@Component
@ConditionalOnExpression("'${llm.provider:mock}' == 'openai-compatible' or '${llm.provider:mock}' == 'real'")
public class OpenAiCompatibleLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleLlmClient.class);

    /** Provider 标识（与 {@code ChatRequest} 8.1.2 协议名一致）。 */
    public static final String PROVIDER = "openai-compatible";

    private static final String CHAT_PATH = "/v1/chat/completions";
    private static final String VISION_PATH = "/v1/chat/completions";

    private final RestClient restClient;
    private final LlmProperties properties;
    private final CircuitBreaker circuitBreaker;

    /**
     * 构造器注入（G-14）。
     *
     * @param llmRestClient       共享的 LLM 出站客户端（含超时与 traceId 透传）
     * @param properties          LLM 配置
     * @param circuitBreakerRegistry Resilience4j 熔断注册表
     */
    public OpenAiCompatibleLlmClient(RestClient llmRestClient, LlmProperties properties,
                                     CircuitBreakerRegistry circuitBreakerRegistry) {
        this.restClient = llmRestClient;
        this.properties = properties;
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("llm");
        log.info("OpenAiCompatibleLlmClient 已装配（provider={} baseUrl={}）", PROVIDER, properties.baseUrl());
    }

    @Override
    public ChatResult chat(ChatRequest request) throws LlmException {
        String model = (request.model() == null || request.model().isBlank())
                ? properties.model() : request.model();
        String body = buildChatBody(model, request.messages(), request.tools(), request.toolChoice());
        return execute("openai-compatible-chat", () -> {
            String raw = restClient.post()
                    .uri(CHAT_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + properties.apiKey())
                    .body(body)
                    .retrieve()
                    .body(String.class);
            return parseChatResult(raw);
        });
    }

    @Override
    public VisionResult vision(VisionRequest request) throws LlmException {
        throw new LlmUnavailableException("视觉能力在 real 模式下尚未启用（MVP 契约占位）");
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    /** 统一异常转译 + 熔断包装。 */
    private <T> T execute(String name, Supplier<T> supplier) throws LlmException {
        try {
            return circuitBreaker.executeSupplier(supplier);
        } catch (RestClientResponseException e) {
            log.warn("LLM 上游返回错误状态: name={} status={}", name, e.getStatusCode().value());
            throw new LlmUnavailableException("模型服务暂不可用", e);
        } catch (ResourceAccessException e) {
            log.warn("LLM 访问异常（疑似超时）: name={} err={}", name, e.getMessage());
            throw new LlmTimeoutException("模型服务响应超时", e);
        } catch (io.github.resilience4j.circuitbreaker.CallNotPermittedException e) {
            log.warn("LLM 熔断打开，拒绝调用: name={}", name);
            throw new LlmUnavailableException("模型服务已熔断", e);
        } catch (LlmException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("LLM 调用失败: name={} err={}", name, e.getMessage());
            throw new LlmUnavailableException("模型服务调用失败", e);
        }
    }

    private String buildChatBody(String model, List<ChatMessage> messages,
                                 List<JsonNode> tools, String toolChoice) {
        ObjectNode root = JsonUtils.mapper().createObjectNode();
        root.put("model", model);
        root.put("stream", false);

        ArrayNode messageArray = root.putArray("messages");
        for (ChatMessage message : messages) {
            ObjectNode node = messageArray.addObject();
            node.put("role", message.role());
            if (message.content() != null) {
                node.put("content", message.content());
            }
            if (message.toolCallId() != null) {
                node.put("tool_call_id", message.toolCallId());
            }
            if (message.name() != null) {
                node.put("name", message.name());
            }
            if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                ArrayNode calls = node.putArray("tool_calls");
                for (ToolCall call : message.toolCalls()) {
                    ObjectNode callNode = calls.addObject();
                    callNode.put("id", call.id());
                    callNode.put("type", call.type() == null ? "function" : call.type());
                    ObjectNode function = callNode.putObject("function");
                    function.put("name", call.functionName());
                    function.put("arguments", call.argumentsJson() == null ? "{}" : call.argumentsJson());
                }
            }
        }

        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolArray = root.putArray("tools");
            for (JsonNode tool : tools) {
                toolArray.add(tool);
            }
            if (toolChoice != null && !toolChoice.isBlank()) {
                root.put("tool_choice", toolChoice);
            }
        }
        return root.toString();
    }

    private ChatResult parseChatResult(String raw) throws LlmException {
        if (raw == null || raw.isBlank()) {
            throw new LlmProtocolException("模型返回空响应");
        }
        JsonNode root = JsonUtils.readTree(raw);
        if (root == null) {
            throw new LlmProtocolException("模型响应非合法 JSON");
        }
        JsonNode choices = root.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            throw new LlmProtocolException("模型响应缺少 choices");
        }
        JsonNode choice = choices.get(0);
        JsonNode message = choice.get("message");
        if (message == null) {
            throw new LlmProtocolException("模型响应缺少 message");
        }
        String content = message.hasNonNull("content") ? message.get("content").asText() : null;
        String finishReason = choice.hasNonNull("finish_reason") ? choice.get("finish_reason").asText() : null;

        List<ToolCall> toolCalls = new ArrayList<>();
        JsonNode callsNode = message.get("tool_calls");
        if (callsNode != null && callsNode.isArray()) {
            for (JsonNode call : callsNode) {
                JsonNode function = call.get("function");
                toolCalls.add(new ToolCall(
                        call.hasNonNull("id") ? call.get("id").asText() : null,
                        call.hasNonNull("type") ? call.get("type").asText() : "function",
                        function != null && function.hasNonNull("name") ? function.get("name").asText() : null,
                        function != null && function.hasNonNull("arguments")
                                ? function.get("arguments").asText() : "{}"));
            }
        }

        TokenUsage usage = TokenUsage.EMPTY;
        JsonNode usageNode = root.get("usage");
        if (usageNode != null) {
            usage = new TokenUsage(
                    usageNode.path("prompt_tokens").asInt(0),
                    usageNode.path("completion_tokens").asInt(0),
                    usageNode.path("total_tokens").asInt(0));
        }
        return new ChatResult(content, toolCalls, usage, finishReason, raw);
    }
}
