package com.lumensteward.clawbot.application.dispatcher;

import com.lumensteward.clawbot.application.admin.UserStatusGate;
import com.lumensteward.clawbot.application.anomaly.AnomalyNotice;
import com.lumensteward.clawbot.application.fallback.FallbackReason;
import com.lumensteward.clawbot.application.fallback.FallbackService;
import com.lumensteward.clawbot.common.enums.AnomalyLayer;
import com.lumensteward.clawbot.common.util.MaskUtils;
import com.lumensteward.clawbot.infrastructure.client.wechat.model.InternalMessage;
import com.lumensteward.clawbot.infrastructure.persistence.service.AnomalyEventService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 消息分发器（架构 5.2 / SRS 9.4.6(3)，BR-05 单一入口）。
 *
 * <p>微信回调只暴露一个端点，内部按消息类型分派到 {@link MessageHandler} 策略族。未知类型
 * <b>不</b>抛异常，回落默认文本处理（SRS 2.3.5 L1：记录日志走默认文本处理，AC-A6 不 500）。
 */
@Component
public class MessageDispatcher {

    private static final Logger log = LoggerFactory.getLogger(MessageDispatcher.class);

    /** 默认类型（未知类型回落）。 */
    private static final String DEFAULT_TYPE = "text";

    /** 无兜底服务（standalone 构造）时的禁用提示。 */
    static final String USER_DISABLED_REPLY = "该账号已被禁用，暂时无法使用。";

    private final Map<String, MessageHandler> handlers;
    private final UserStatusGate userStatusGate;
    private final FallbackService fallbackService;
    /** 四层异常埋点写入（可为 null，兼容独立构造）。 */
    private final AnomalyEventService anomalyEventService;

    /**
     * Spring 装配用构造器（G-14 / FR-23 同构的插件化）。
     *
     * <p>迭代 2 T11：追加 {@link UserStatusGate}——被禁用用户在<b>分发之前</b>即被拦截，
     * 从而"不触发 LLM"（FR-16 AC②）。迭代 4 W1：追加 {@link AnomalyEventService}，
     * 把未知消息类型落为 L1 接入层异常事实（SRS 2.3.5）。
     *
     * @param handlerList         Spring 容器内全部处理器
     * @param userStatusGate      用户状态闸门（可为 null，兼容独立构造）
     * @param fallbackService     兜底文案（可为 null）
     * @param anomalyEventService 四层异常埋点（可为 null）
     */
    @Autowired
    public MessageDispatcher(List<MessageHandler> handlerList, UserStatusGate userStatusGate,
                             FallbackService fallbackService, AnomalyEventService anomalyEventService) {
        Map<String, MessageHandler> map = new LinkedHashMap<>();
        if (handlerList != null) {
            for (MessageHandler handler : handlerList) {
                String key = normalize(handler.supportsMsgType());
                MessageHandler previous = map.putIfAbsent(key, handler);
                if (previous != null) {
                    throw new IllegalStateException("消息处理器类型冲突: " + key);
                }
            }
        }
        this.handlers = Collections.unmodifiableMap(map);
        this.userStatusGate = userStatusGate;
        this.fallbackService = fallbackService;
        this.anomalyEventService = anomalyEventService;
        log.info("MessageDispatcher 初始化完成，已注册类型={}", this.handlers.keySet());
    }

    /**
     * 兼容构造（无状态闸门与埋点）：保留给脱离 Spring 上下文的单元测试。
     *
     * @param handlerList Spring 容器内全部处理器
     */
    public MessageDispatcher(List<MessageHandler> handlerList) {
        this(handlerList, null, null, null);
    }

    /**
     * 分发消息（BR-05 单一入口）。
     *
     * @param message 内部消息
     * @return 回复文本；null/空表示无需被动回复
     */
    public String dispatch(InternalMessage message) {
        if (message == null) {
            log.warn("分发空消息，忽略");
            return null;
        }
        if (userStatusGate != null && userStatusGate.isBlocked(message.openid())) {
            log.warn("用户已禁用，拦截（不触发 LLM，FR-16 AC②）: openid={}",
                    MaskUtils.openid(message.openid()));
            return fallbackService == null ? USER_DISABLED_REPLY
                    : fallbackService.render(FallbackReason.USER_DISABLED, Map.of());
        }
        String type = normalize(message.msgType());
        MessageHandler handler = handlers.get(type);
        if (handler == null) {
            log.warn("未知消息类型 type={} openid={}，回落默认文本处理（不 500）",
                    type, MaskUtils.openid(message.openid()));
            if (anomalyEventService != null) {
                // SRS 2.3.5 L1「非法/未知消息类型」：回落不报错，但必须留下可查事实
                anomalyEventService.record(new AnomalyNotice(AnomalyLayer.L1, "UNKNOWN_MSG_TYPE",
                        "dispatcher", message.openid(), "type=" + type));
            }
            handler = handlers.get(DEFAULT_TYPE);
        }
        if (handler == null) {
            log.error("无可用处理器（含默认），返回空回复");
            return null;
        }
        try {
            return handler.handle(message);
        } catch (RuntimeException e) {
            // 处理器异常不得冒泡为 500（L1/L2 兜底由上层保证）
            log.error("消息处理异常 type={} err={}", type, e.getMessage(), e);
            return null;
        }
    }

    /** 已注册的消息类型集合。 */
    public Set<String> supportedTypes() {
        return handlers.keySet();
    }

    private static String normalize(String type) {
        return type == null || type.isBlank() ? DEFAULT_TYPE : type.trim().toLowerCase(Locale.ROOT);
    }
}
