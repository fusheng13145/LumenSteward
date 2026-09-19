package com.lumensteward.clawbot.dispatcher;

import com.lumensteward.clawbot.application.admin.UserStatusGate;
import com.lumensteward.clawbot.application.dispatcher.MessageDispatcher;
import com.lumensteward.clawbot.application.dispatcher.MessageHandler;
import com.lumensteward.clawbot.application.fallback.DefaultFallbackService;
import com.lumensteward.clawbot.infrastructure.client.wechat.model.InternalMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 禁用用户闸门在分发层的行为测试（FR-16 AC② / T11）。
 *
 * <p>判据：被禁用用户的消息<b>不进入任何 MessageHandler</b>——即不触发 LLM、不产生工具调用。
 */
class UserStatusGateDispatchTest {

    private final MessageHandler handler = mock(MessageHandler.class);
    private final UserStatusGate gate = mock(UserStatusGate.class);

    @Test
    @DisplayName("禁用用户：拦截，处理器不被调用（不触发 LLM）")
    void shouldBlockDisabledUserBeforeHandler() {
        when(gate.isBlocked("openid-1")).thenReturn(true);
        when(handler.supportsMsgType()).thenReturn("text");
        MessageDispatcher dispatcher =
                new MessageDispatcher(List.of(handler), gate, new DefaultFallbackService());

        String reply = dispatcher.dispatch(message("openid-1"));

        assertThat(reply).contains("禁用");
        verify(handler, never()).handle(any());
    }

    @Test
    @DisplayName("正常用户：照常分发到处理器")
    void shouldDispatchForActiveUser() {
        when(gate.isBlocked("openid-2")).thenReturn(false);
        when(handler.supportsMsgType()).thenReturn("text");
        when(handler.handle(any())).thenReturn("已帮你查询");
        MessageDispatcher dispatcher =
                new MessageDispatcher(List.of(handler), gate, new DefaultFallbackService());

        assertThat(dispatcher.dispatch(message("openid-2"))).isEqualTo("已帮你查询");
        verify(handler).handle(any());
    }

    @Test
    @DisplayName("无闸门（兼容构造）：行为与既有一致，不做拦截")
    void shouldAllowWhenGateAbsent() {
        when(handler.supportsMsgType()).thenReturn("text");
        when(handler.handle(any())).thenReturn("已帮你查询");
        MessageDispatcher dispatcher = new MessageDispatcher(List.of(handler));

        assertThat(dispatcher.dispatch(message("openid-3"))).isEqualTo("已帮你查询");
    }

    private static InternalMessage message(String openid) {
        return new InternalMessage(openid, "text", "msg-1", "帮我查快递", null, null, null,
                System.currentTimeMillis() / 1000, Map.of());
    }
}
