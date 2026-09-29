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
 * 图片消息处理器（FR-02 → FR-10；W11 升级：由占位提示改为接入对话引擎）。
 *
 * <p>把图片消息合成为一条携带图片链接的用户消息交由 {@link AgentOrchestrator} 编排：
 * 模型在函数 Schema 里看到 {@code recognize_image} 即发起识别，识别结论写入识图缓存
 * （W11），后续追问经 {@code ask_image} 免重发图片续接（D1 场景 S2 的通道前提）。
 * 识别由模型驱动的工具完成，本处理器<b>不直接调用视觉接口、不臆造识别结果</b>（BR-09）。
 *
 * <p>另保留 {@link #ImageMessageHandler()} 无参构造：仅供独立构造（无 Spring 上下文）时使用，
 * 此时返回可读兜底文本，<b>绝不返回 null、绝不抛异常</b>（BR-04「不编造、不 500」）。
 */
@Component
public class ImageMessageHandler implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(ImageMessageHandler.class);

    /** 无编排器（standalone 构造）时的兜底回复。 */
    static final String ORCHESTRATOR_UNAVAILABLE_REPLY = "我暂时还不能帮你识别图片哦，可以先告诉我这是什么吗？";

    private final AgentOrchestrator orchestrator;
    private final SessionResolver sessionResolver;

    /**
     * Spring 装配用的构造器（G-14 构造器注入）。
     *
     * @param orchestrator    Agent 编排器（对话引擎）
     * @param sessionResolver 会话解析器（解析/创建 sessionId）
     */
    @Autowired
    public ImageMessageHandler(AgentOrchestrator orchestrator, SessionResolver sessionResolver) {
        this.orchestrator = orchestrator;
        this.sessionResolver = sessionResolver;
    }

    /**
     * 独立构造（无编排器）：保留以便脱离 Spring 上下文的单元构造；此时 {@link #handle} 返回兜底文本。
     */
    public ImageMessageHandler() {
        this.orchestrator = null;
        this.sessionResolver = null;
    }

    @Override
    public String supportsMsgType() {
        return "image";
    }

    @Override
    public String handle(InternalMessage message) {
        if (message == null) {
            return null;
        }
        if (orchestrator == null) {
            log.warn("ImageMessageHandler 未注入 AgentOrchestrator（standalone 构造），返回兜底回复");
            return ORCHESTRATOR_UNAVAILABLE_REPLY;
        }

        Long sessionId = sessionResolver == null ? null : sessionResolver.resolveOrCreate(message.openid());
        OrchestrationRequest request = new OrchestrationRequest(
                TraceContext.getTraceId(), message.openid(), sessionId, synthesizedContent(message), List.of());

        long start = System.currentTimeMillis();
        OrchestrationResult result = orchestrator.run(request);
        String reply = result == null ? null : result.replyText();
        log.info("图片消息经对话引擎产出回复 openid={} rounds={} degraded={} latency={}ms",
                MaskUtils.openid(message.openid()), result == null ? 0 : result.rounds(),
                result != null && result.degraded(), System.currentTimeMillis() - start);
        return reply;
    }

    /**
     * 合成进编排器的用户消息：携带图片链接供识别工具取用；无链接时如实说明（不伪造 URL）。
     */
    private static String synthesizedContent(InternalMessage message) {
        String picUrl = message.picUrl();
        if (picUrl == null || picUrl.isBlank()) {
            return "（用户发来一张图片，但未附带链接）";
        }
        return "（用户发来一张图片，链接：" + picUrl + "）";
    }
}
