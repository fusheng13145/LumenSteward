package com.lumensteward.clawbot.application.dispatcher;

import com.lumensteward.clawbot.infrastructure.client.wechat.model.InternalMessage;

/**
 * 消息类型处理策略（架构 5.2 / SRS 9.4.6(3)，FR-02）。
 *
 * <p>「入口统一、类型参数化、实现策略化」：新增消息类型只需新增一个策略 Bean，无需改动分发逻辑。
 *
 * <p><b>返回值说明（对架构 5.2 void 签名的有意收敛）：</b>返回面向用户的回复文本（可为 null 表示
 * 无需被动回复）。这样被动回复由控制器统一渲染，避免以 ThreadLocal 传递副信道。
 */
public interface MessageHandler {

    /**
     * 支持的消息类型（线值：text/image/voice/location/event）。
     *
     * @return 类型线值
     */
    String supportsMsgType();

    /**
     * 处理消息。
     *
     * @param message 内部消息
     * @return 回复文本；null/空表示无需被动回复
     */
    String handle(InternalMessage message);
}
