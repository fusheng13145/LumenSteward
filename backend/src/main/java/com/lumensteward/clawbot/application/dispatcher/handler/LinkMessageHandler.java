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
 * 链接消息处理器（迭代 4 W16）：把 link 消息（标题 + 地址）合成为用户消息交
 * {@link AgentOrchestrator} 编排——模型在函数 Schema 里看到 {@code read_link}（本轮含链接
 * 时可见，W16 情境门控）即发起抓取，正文总结由模型完成，本处理器<b>不抓取、不臆造摘要</b>
 * （BR-09）。缺地址的消息如实说明，不伪造 URL。
 *
 * <p>另保留 {@link #LinkMessageHandler()} 无参构造：独立构造（无 Spring 上下文）时返回
 * 可读兜底文本，<b>绝不返回 null、绝不抛异常</b>（BR-04）。
 */
@Component
public class LinkMessageHandler implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(LinkMessageHandler.class);

    /** 无编排器（standalone 构造）时的兜底回复。 */
    static final String ORCHESTRATOR_UNAVAILABLE_REPLY = "我暂时还不能帮你读链接，稍后再试试～";

    private final AgentOrchestrator orchestrator;
    private final SessionResolver sessionResolver;

    /**
     * Spring 装配用的构造器（G-14 构造器注入）。
     *
     * @param orchestrator    Agent 编排器（对话引擎）
     * @param sessionResolver 会话解析器（解析/创建 sessionId）
     */
    @Autowired
    public LinkMessageHandler(AgentOrchestrator orchestrator, SessionResolver sessionResolver) {
        this.orchestrator = orchestrator;
        this.sessionResolver = sessionResolver;
    }

    /** 独立构造（无编排器）：脱离 Spring 上下文时 {@link #handle} 返回兜底文本。 */
    public LinkMessageHandler() {
        this.orchestrator = null;
        this.sessionResolver = null;
    }

    @Override
    public String supportsMsgType() {
        return "link";
    }

    @Override
    public String handle(InternalMessage message) {
        if (message == null) {
            return null;
        }
        if (orchestrator == null) {
            log.warn("LinkMessageHandler 未注入 AgentOrchestrator（standalone 构造），返回兜底回复");
            return ORCHESTRATOR_UNAVAILABLE_REPLY;
        }

        Long sessionId = sessionResolver == null ? null : sessionResolver.resolveOrCreate(message.openid());
        OrchestrationRequest request = new OrchestrationRequest(
                TraceContext.getTraceId(), message.openid(), sessionId, synthesizedContent(message), List.of());

        long start = System.currentTimeMillis();
        OrchestrationResult result = orchestrator.run(request);
        String reply = result == null ? null : result.replyText();
        log.info("链接消息经对话引擎产出回复 openid={} rounds={} degraded={} latency={}ms",
                MaskUtils.openid(message.openid()), result == null ? 0 : result.rounds(),
                result != null && result.degraded(), System.currentTimeMillis() - start);
        return reply;
    }

    /**
     * 合成进编排器的用户消息：携带标题与地址（含 http 前缀即触发 read_link 可见性）；
     * 缺地址时如实说明（不伪造 URL）。
     */
    private static String synthesizedContent(InternalMessage message) {
        String url = message.linkUrl();
        String title = message.content() == null || message.content().isBlank()
                ? "" : message.content();
        if (url == null || url.isBlank()) {
            return title.isBlank()
                    ? "（用户分享了一个链接，但未附带地址）"
                    : "（用户分享了一个链接《" + title + "》，但未附带地址）";
        }
        return title.isBlank()
                ? "（用户分享了一个链接，地址：" + url + "，帮我读一下并总结要点）"
                : "（用户分享了一个链接《" + title + "》，地址：" + url + "，帮我读一下并总结要点）";
    }
}
