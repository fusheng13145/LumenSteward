package com.lumensteward.clawbot.infrastructure.client.llm.script;

import com.lumensteward.clawbot.infrastructure.client.llm.dto.ChatMessage;
import com.lumensteward.clawbot.infrastructure.client.llm.dto.ChatResult;
import com.lumensteward.clawbot.infrastructure.client.llm.dto.TokenUsage;
import com.lumensteward.clawbot.infrastructure.client.llm.dto.ToolCall;
import com.lumensteward.clawbot.infrastructure.client.llm.exception.LlmException;
import com.lumensteward.clawbot.infrastructure.client.llm.exception.LlmProtocolException;
import com.lumensteward.clawbot.infrastructure.client.llm.exception.LlmTimeoutException;
import com.lumensteward.clawbot.infrastructure.client.llm.exception.LlmUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 可编程 Mock 脚本（架构 5.1 / SRS 9.4.1）。
 *
 * <p><b>可编程性是硬要求：</b>Mock 必须能被脚本/配置驱动，按需返回指定的 {@code tool_calls}、
 * 终态文本、或注入 {@code 失败/超时/非法输出}，以支撑 AC-D1 与 TC-H（反幻觉）用例。
 *
 * <p>脚本文件为 {@code mock/llm-scripts.yml}，结构：
 * <pre>
 * defaultContent: "..."        # 无规则命中时的默认文本
 * defaultFinishReason: stop
 * rules:
 *   - name: 规则名
 *     round: 0                 # 可选：0 基轮次过滤（不填=不限）
 *     match: "关键词"          # 可选：匹配最近一条 user 消息的子串
 *     failure: TIMEOUT         # 可选：TIMEOUT / UNAVAILABLE / PROTOCOL
 *     finishReason: tool_calls # 可选
 *     content: "..."           # 可选
 *     toolCalls:               # 可选
 *       - id: c1
 *         name: manage_pet_profile
 *         arguments: '{"action":"READ"}'
 * </pre>
 *
 * <p>"轮次" 的推定：{@code chat} 入参不含轮次，故 Mock 以「消息序列中带 toolCalls 的 assistant
 * 消息数量」作为当前轮次（与 Agent Loop 每轮至多一次工具调用吻合，SRS 9.4.3）。
 */
public final class MockScript {

    private static final Logger log = LoggerFactory.getLogger(MockScript.class);

    private final List<Rule> rules;
    private final String defaultContent;
    private final String defaultFinishReason;

    private MockScript(List<Rule> rules, String defaultContent, String defaultFinishReason) {
        this.rules = rules == null ? List.of() : List.copyOf(rules);
        this.defaultContent = defaultContent == null ? "（Mock）你好呀，我是衔光管家～" : defaultContent;
        this.defaultFinishReason = defaultFinishReason == null ? "stop" : defaultFinishReason;
    }

    /**
     * 从 classpath 资源加载脚本。
     *
     * @param path classpath 路径（如 {@code mock/llm-scripts.yml}）
     * @return 脚本实例；资源缺失或解析失败时回落到空脚本（不阻断启动）
     */
    @SuppressWarnings("unchecked")
    public static MockScript fromResource(String path) {
        Resource resource = new ClassPathResource(path);
        if (!resource.exists()) {
            log.warn("Mock 脚本资源不存在，使用空脚本: {}", path);
            return empty();
        }
        try (InputStream in = resource.getInputStream()) {
            Object loaded = new Yaml().load(in);
            if (!(loaded instanceof Map)) {
                log.warn("Mock 脚本结构非 Map，使用空脚本: {}", path);
                return empty();
            }
            Map<String, Object> root = (Map<String, Object>) loaded;
            String defaultContent = asString(root.get("defaultContent"));
            String defaultFinishReason = asString(root.get("defaultFinishReason"));
            List<Rule> rules = new ArrayList<>();
            Object rulesNode = root.get("rules");
            if (rulesNode instanceof List) {
                for (Object item : (List<Object>) rulesNode) {
                    if (item instanceof Map) {
                        rules.add(parseRule((Map<String, Object>) item));
                    }
                }
            }
            log.info("Mock 脚本加载完成: path={} rules={}", path, rules.size());
            return new MockScript(rules, defaultContent, defaultFinishReason);
        } catch (IOException | RuntimeException e) {
            log.warn("Mock 脚本加载失败，使用空脚本: path={} err={}", path, e.getMessage());
            return empty();
        }
    }

    /** 空脚本（默认回复）。 */
    public static MockScript empty() {
        return new MockScript(List.of(), null, null);
    }

    /**
     * 解析下一轮响应（命中规则则按其返回；规则含 failure 时抛出对应异常）。
     *
     * @param round    当前轮次（0 基）
     * @param messages 消息序列
     * @return 对话结果
     * @throws LlmException 命中注入失败规则时抛出
     */
    public ChatResult next(int round, List<ChatMessage> messages) throws LlmException {
        String lastUserContent = lastUserContent(messages);
        for (Rule rule : rules) {
            if (!rule.matches(round, lastUserContent)) {
                continue;
            }
            if (rule.failure() != null) {
                throw buildFailure(rule.failure());
            }
            String finish = rule.finishReason() != null
                    ? rule.finishReason()
                    : (rule.toolCalls().isEmpty() ? "stop" : "tool_calls");
            return new ChatResult(rule.content(), rule.toolCalls(), TokenUsage.EMPTY, finish, null);
        }
        return new ChatResult(defaultContent, List.of(), TokenUsage.EMPTY, defaultFinishReason, null);
    }

    private static LlmException buildFailure(String type) {
        String normalized = type.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "TIMEOUT" -> new LlmTimeoutException("Mock 注入：模型响应超时");
            case "UNAVAILABLE" -> new LlmUnavailableException("Mock 注入：模型服务不可用");
            case "PROTOCOL" -> new LlmProtocolException("Mock 注入：模型输出格式非法");
            default -> new LlmProtocolException("Mock 注入未知故障类型: " + type);
        };
    }

    private static String lastUserContent(List<ChatMessage> messages) {
        if (messages == null) {
            return "";
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage message = messages.get(i);
            if (ChatMessage.ROLE_USER.equals(message.role()) && message.content() != null) {
                return message.content();
            }
        }
        return "";
    }

    @SuppressWarnings("unchecked")
    private static Rule parseRule(Map<String, Object> node) {
        String name = asString(node.get("name"));
        Integer round = asInteger(node.get("round"));
        String match = asString(node.get("match"));
        String failure = asString(node.get("failure"));
        String content = asString(node.get("content"));
        String finishReason = asString(node.get("finishReason"));
        List<ToolCall> toolCalls = new ArrayList<>();
        Object callsNode = node.get("toolCalls");
        if (callsNode instanceof List) {
            for (Object item : (List<Object>) callsNode) {
                if (item instanceof Map) {
                    Map<String, Object> call = (Map<String, Object>) item;
                    toolCalls.add(new ToolCall(
                            asString(call.get("id")),
                            call.get("type") == null ? "function" : asString(call.get("type")),
                            asString(call.get("name")),
                            asString(call.get("arguments"))));
                }
            }
        }
        return new Rule(name, round, match, failure, content, finishReason, toolCalls);
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Integer asInteger(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 单条脚本规则。
     *
     * @param name         规则名（排障）
     * @param round        轮次过滤（null=不限）
     * @param match        最近 user 消息子串过滤（null=不限）
     * @param failure      注入故障类型（null=正常）
     * @param content      终态文本
     * @param finishReason 结束原因
     * @param toolCalls    工具调用
     */
    private record Rule(String name, Integer round, String match, String failure,
                        String content, String finishReason, List<ToolCall> toolCalls) {

        boolean matches(int currentRound, String lastUserContent) {
            if (round != null && round != currentRound) {
                return false;
            }
            if (match != null && !match.isBlank()) {
                return lastUserContent != null && lastUserContent.contains(match);
            }
            return true;
        }
    }
}
