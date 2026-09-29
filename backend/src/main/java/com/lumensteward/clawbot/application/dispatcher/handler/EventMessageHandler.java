package com.lumensteward.clawbot.application.dispatcher.handler;

import com.lumensteward.clawbot.application.capability.CapabilitySections;
import com.lumensteward.clawbot.application.config.ConfigKeys;
import com.lumensteward.clawbot.application.config.DynamicConfigService;
import com.lumensteward.clawbot.application.dispatcher.MessageHandler;
import com.lumensteward.clawbot.application.orchestrator.AgentOrchestrator;
import com.lumensteward.clawbot.application.orchestrator.model.OrchestrationRequest;
import com.lumensteward.clawbot.application.orchestrator.model.OrchestrationResult;
import com.lumensteward.clawbot.common.util.MaskUtils;
import com.lumensteward.clawbot.domain.tool.ToolRegistry;
import com.lumensteward.clawbot.domain.tool.ToolVisibilityContext;
import com.lumensteward.clawbot.infrastructure.client.wechat.model.InternalMessage;
import com.lumensteward.clawbot.infrastructure.observability.TraceContext;
import com.lumensteward.clawbot.infrastructure.persistence.support.SessionResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 事件消息处理器（FR-02 备选流 3b + W20 首发体验）。
 *
 * <p><b>W20 交付口径（D2 的 P6 判据承担者）：</b>
 * <ul>
 *   <li><b>subscribe</b> → 欢迎语：正文经 FR-18 在线可配（{@code wechat.welcome-message}），
 *       能力段由 {@link CapabilitySections} 按「实际可下发工具集」生成——欢迎语只承诺真话，
 *       条件可见工具（如 {@code ask_image}）在最小上下文下自然不出现在清单里；</li>
 *   <li><b>CLICK</b> → 菜单点击不再静默丢弃：EventKey 为「帮助/help」时确定性返回能力自述
 *       （与帮助指令同口径）；其余 EventKey 作为用户消息路由进对话引擎（点菜单即触发真实动作）；</li>
 *   <li><b>view</b> → 菜单跳转确认（EventKey 为 URL，页面本身即响应，补一条可观测的确认回复）；</li>
 *   <li><b>unsubscribe 及其余事件</b> → 静默（不进入对话引擎，维持既有语义）。</li>
 * </ul>
 *
 * <p>standalone 无参构造保留：无编排器/配置源时欢迎语回落静态默认、CLICK 返回兜底文本，
 * 绝不返回未定义行为（BR-04）。
 */
@Component
public class EventMessageHandler implements MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(EventMessageHandler.class);

    /** 欢迎语默认正文（V1.0.17 播种值与此逐字相等 ⇒ 行为零变更，可在后台热改）。 */
    static final String DEFAULT_WELCOME = "你好呀，我是衔光管家～有任何想聊的、想记的，都可以直接告诉我。";

    /** 能力清单引导语（欢迎语与帮助共用的组合方式：正文 + 能力段）。 */
    private static final String CAPABILITY_LEAD = "\n\n我能帮你：\n";

    private static final String VIEW_REPLY_PREFIX = "菜单页面已打开：";

    private final AgentOrchestrator orchestrator;
    private final SessionResolver sessionResolver;
    private final ToolRegistry toolRegistry;
    private final DynamicConfigService dynamicConfig;

    /**
     * Spring 装配构造器（G-14）。
     *
     * @param orchestrator    对话引擎（CLICK 菜单路由）
     * @param sessionResolver 会话解析器
     * @param toolRegistry    工具注册表（能力自述真值来源）
     * @param dynamicConfig   动态配置（欢迎语可配 + 禁用工具集）
     */
    @Autowired
    public EventMessageHandler(AgentOrchestrator orchestrator, SessionResolver sessionResolver,
                               ToolRegistry toolRegistry, DynamicConfigService dynamicConfig) {
        this.orchestrator = orchestrator;
        this.sessionResolver = sessionResolver;
        this.toolRegistry = toolRegistry;
        this.dynamicConfig = dynamicConfig;
    }

    /** standalone 构造（无依赖）：欢迎语回落默认正文、无能力段，CLICK 回兜底文本。 */
    public EventMessageHandler() {
        this.orchestrator = null;
        this.sessionResolver = null;
        this.toolRegistry = null;
        this.dynamicConfig = null;
    }

    @Override
    public String supportsMsgType() {
        return "event";
    }

    @Override
    public String handle(InternalMessage message) {
        String event = message == null ? null : message.event();
        log.info("收到事件消息 event={}", event);
        if ("subscribe".equalsIgnoreCase(event)) {
            return welcome();
        }
        if ("CLICK".equalsIgnoreCase(event)) {
            return onMenuClick(message);
        }
        if ("view".equalsIgnoreCase(event)) {
            String key = message.eventAttributes().get("EventKey");
            return key == null || key.isBlank() ? null : VIEW_REPLY_PREFIX + key;
        }
        // unsubscribe 及其他事件：静默处理，不进入对话引擎
        return null;
    }

    /**
     * 欢迎语（W20）：正文可配 + 能力自述段（实际可下发集；禁用/条件可见工具不出现）。
     */
    private String welcome() {
        String body = dynamicConfig == null
                ? DEFAULT_WELCOME
                : dynamicConfig.getString(ConfigKeys.WECHAT_WELCOME_MESSAGE, DEFAULT_WELCOME);
        return body + CAPABILITY_LEAD
                + CapabilitySections.forUser(toolRegistry, disabledTools(), ToolVisibilityContext.of(null));
    }

    /**
     * 菜单点击（W20）：EventKey「帮助/help」确定性回能力自述；其余路由进对话引擎。
     */
    private String onMenuClick(InternalMessage message) {
        String eventKey = message.eventAttributes().get("EventKey");
        if (eventKey == null || eventKey.isBlank()) {
            log.warn("菜单 CLICK 事件缺少 EventKey，忽略 openid={}", MaskUtils.openid(message.openid()));
            return null;
        }
        if ("帮助".equalsIgnoreCase(eventKey.trim()) || "help".equalsIgnoreCase(eventKey.trim())) {
            return capabilitySelfDescription();
        }
        if (orchestrator == null) {
            log.warn("EventMessageHandler 未注入 AgentOrchestrator（standalone 构造），菜单点击返回兜底回复");
            return "菜单已收到，我稍后帮你处理这个入口～";
        }
        Long sessionId = sessionResolver == null ? null : sessionResolver.resolveOrCreate(message.openid());
        OrchestrationRequest request = new OrchestrationRequest(
                TraceContext.getTraceId(), message.openid(), sessionId, eventKey, List.of());
        OrchestrationResult result = orchestrator.run(request);
        log.info("菜单 CLICK 已路由进对话引擎 openid={} eventKey={} rounds={}",
                MaskUtils.openid(message.openid()), eventKey, result == null ? 0 : result.rounds());
        return result == null ? null : result.replyText();
    }

    /**
     * 能力自述（与编排器「帮助」指令同口径，W12 真值化）。
     */
    private String capabilitySelfDescription() {
        return "我是衔光管家，以下是我当前实际能帮你做的事：\n"
                + CapabilitySections.forUser(toolRegistry, disabledTools(), ToolVisibilityContext.of(null))
                + "\n直接用一句话告诉我就行。";
    }

    /**
     * 运行期禁用工具集（FR-18）；配置源缺失按空集（standalone 兜底）。
     */
    private Set<String> disabledTools() {
        if (dynamicConfig == null) {
            return Set.of();
        }
        return Set.copyOf(dynamicConfig.getList(ConfigKeys.ORCHESTRATION_DISABLED_TOOLS, List.of()));
    }
}
