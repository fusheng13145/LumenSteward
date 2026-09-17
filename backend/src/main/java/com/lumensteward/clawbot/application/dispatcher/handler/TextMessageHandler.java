package com.lumensteward.clawbot.application.dispatcher.handler;

import com.lumensteward.clawbot.application.dispatcher.MessageHandler;
import com.lumensteward.clawbot.application.orchestrator.AgentOrchestrator;
import com.lumensteward.clawbot.application.orchestrator.model.OrchestrationRequest;
import com.lumensteward.clawbot.application.orchestrator.model.OrchestrationResult;
import com.lumensteward.clawbot.common.util.MaskUtils;
import com.lumensteward.clawbot.infrastructure.client.wechat.model.InternalMessage;
import com.lumensteward.clawbot.infrastructure.observability.TraceContext;
import com.lumensteward.clawbot.infrastructure.persistence.support.SessionResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 文本消息处理器（FR-02 → FR-04 对话引擎入口）。
 *
 * <p><b>D1 修复：真正接入对话引擎。</b>本处理器注入 {@link AgentOrchestrator}，将文本消息
 * 交由编排器执行（意图识别 → 工具编排 → 真实工具执行 → 输出治理），并返回<b>终态回复文本</b>。
 * 这是「微信消息 → … → 回复」主链路的关键接线点，不再是"注释里说要调用、实际返回 null"的占位。
 *
 * <p>会话 id 在此解析（{@code wx_session} 为 {@code log_tool_call.session_id}/{@code wx_message.session_id}
 * 的 NOT NULL 逻辑外键），保证工具日志可落库。
 *
 * <p>另保留 {@link #TextMessageHandler()} 无参构造：仅供独立构造（无 Spring 上下文）时使用，
 * 此时无编排器，返回可读兜底文本，<b>绝不返回 null、绝不抛异常</b>（BR-04「不编造、不 500」）。
 */
@Component
public class TextMessageHandler implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(TextMessageHandler.class);

    /** 无编排器（standalone 构造）时的兜底回复。 */
    static final String ORCHESTRATOR_UNAVAILABLE_REPLY = "抱歉，我这会儿有点忙不过来，请稍后再试。";

    private final AgentOrchestrator orchestrator;
    private final SessionResolver sessionResolver;

    /**
     * Spring 装配用的构造器（G-14 构造器注入）。
     *
     * @param orchestrator    Agent 编排器（对话引擎）
     * @param sessionResolver 会话解析器（解析/创建 sessionId）
     */
    @Autowired
    public TextMessageHandler(AgentOrchestrator orchestrator, SessionResolver sessionResolver) {
        this.orchestrator = orchestrator;
        this.sessionResolver = sessionResolver;
    }

    /**
     * 独立构造（无编排器）：保留以便脱离 Spring 上下文的单元构造；此时 {@link #handle} 返回兜底文本。
     */
    public TextMessageHandler() {
        this.orchestrator = null;
        this.sessionResolver = null;
    }

    @Override
    public String supportsMsgType() {
        return "text";
    }

    @Override
    public String handle(InternalMessage message) {
        if (message == null) {
            return null;
        }
        if (orchestrator == null) {
            log.warn("TextMessageHandler 未注入 AgentOrchestrator（standalone 构造），返回兜底回复");
            return ORCHESTRATOR_UNAVAILABLE_REPLY;
        }

        Long sessionId = sessionResolver == null ? null : sessionResolver.resolveOrCreate(message.openid());
        OrchestrationRequest request = new OrchestrationRequest(
                TraceContext.getTraceId(), message.openid(), sessionId, message.content(), List.of());

        long start = System.currentTimeMillis();
        OrchestrationResult result = orchestrator.run(request);
        String reply = result == null ? null : result.replyText();
        log.info("文本消息经对话引擎产出回复 openid={} rounds={} degraded={} latency={}ms",
                MaskUtils.openid(message.openid()), result == null ? 0 : result.rounds(),
                result != null && result.degraded(), System.currentTimeMillis() - start);
        return reply;
    }
}
