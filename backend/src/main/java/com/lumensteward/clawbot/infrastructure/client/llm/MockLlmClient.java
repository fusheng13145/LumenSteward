package com.lumensteward.clawbot.infrastructure.client.llm;

import com.lumensteward.clawbot.infrastructure.client.llm.dto.ChatMessage;
import com.lumensteward.clawbot.infrastructure.client.llm.dto.ChatRequest;
import com.lumensteward.clawbot.infrastructure.client.llm.dto.ChatResult;
import com.lumensteward.clawbot.infrastructure.client.llm.dto.VisionRequest;
import com.lumensteward.clawbot.infrastructure.client.llm.dto.VisionResult;
import com.lumensteward.clawbot.infrastructure.client.llm.exception.LlmException;
import com.lumensteward.clawbot.infrastructure.client.llm.script.MockScript;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Mock LLM 客户端（架构 5.1 / SRS 9.4.1，AC-D1）。
 *
 * <p>当 {@code llm.provider=mock}（默认）时装配。响应完全由 {@link MockScript}（{@code
 * mock/llm-scripts.yml}）确定性驱动：可返回指定 {@code tool_calls}、终态文本，或注入
 * 失败/超时/非法输出，从而在无外网条件下走通全链路（AC-D1）。
 *
 * <p>轮次推定见 {@link MockScript} 类注释。
 */
@Component
@ConditionalOnExpression("'${llm.provider:mock}' == 'mock'")
public class MockLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(MockLlmClient.class);

    /** Mock 脚本资源路径。 */
    private static final String SCRIPT_PATH = "mock/llm-scripts.yml";

    /** Provider 标识。 */
    public static final String PROVIDER = "mock";

    private final MockScript script;

    /** 默认构造：加载 classpath 脚本。 */
    public MockLlmClient() {
        this(MockScript.fromResource(SCRIPT_PATH));
    }

    /**
     * 测试友好构造：注入自定义脚本。
     *
     * @param script Mock 脚本
     */
    public MockLlmClient(MockScript script) {
        this.script = script == null ? MockScript.empty() : script;
        log.info("MockLlmClient 已装配（provider=mock）");
    }

    @Override
    public ChatResult chat(ChatRequest request) throws LlmException {
        List<ChatMessage> messages = request == null ? List.of() : request.messages();
        int round = resolveRound(messages);
        ChatResult result = script.next(round, messages);
        log.debug("MockLlmClient.chat round={} finishReason={} toolCalls={}",
                round, result.finishReason(), result.toolCalls().size());
        return result;
    }

    @Override
    public VisionResult vision(VisionRequest request) throws LlmException {
        // MVP 仅契约；返回可预期的确定性结果（BR-09：不臆造真实识别内容）
        String question = request == null ? null : request.question();
        return new VisionResult("（Mock）我看到的是一张图片。", 0.5,
                null);
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    /**
     * 推定当前轮次：带 toolCalls 的 assistant 消息数量即已发生的工具调用轮次。
     *
     * @param messages 消息序列
     * @return 轮次（0 基）
     */
    private static int resolveRound(List<ChatMessage> messages) {
        int round = 0;
        for (ChatMessage message : messages) {
            if (message.isAssistant() && message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                round++;
            }
        }
        return round;
    }
}
