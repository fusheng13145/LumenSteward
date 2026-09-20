package com.lumensteward.clawbot.dispatcher;

import com.lumensteward.clawbot.application.anomaly.AnomalyNotice;
import com.lumensteward.clawbot.application.dispatcher.MessageDispatcher;
import com.lumensteward.clawbot.application.dispatcher.MessageHandler;
import com.lumensteward.clawbot.application.fallback.DefaultFallbackService;
import com.lumensteward.clawbot.common.enums.AnomalyLayer;
import com.lumensteward.clawbot.infrastructure.client.wechat.model.InternalMessage;
import com.lumensteward.clawbot.infrastructure.persistence.service.AnomalyEventService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 未知消息类型的 L1 埋点测试（SRS 2.3.5 L1「非法/未知消息类型」/ 迭代 4 W1）。
 *
 * <p>回落默认文本处理是既有行为（AC-A6 不 500），本测试只补一条判据：<b>回落必须留下可查事实</b>，
 * 否则 A-3 四层分布在 L1 恒为 0、降级链路「不可见」。
 */
class UnknownMsgTypeAnomalyTest {

    private final MessageHandler textHandler = mock(MessageHandler.class);
    private final AnomalyEventService anomalyEventService = mock(AnomalyEventService.class);
    private final ArgumentCaptor<AnomalyNotice> captor = ArgumentCaptor.forClass(AnomalyNotice.class);

    @Test
    @DisplayName("未知类型：回落文本处理，同时埋 L1 UNKNOWN_MSG_TYPE（含原始类型名）")
    void shouldRecordUnknownTypeAndStillFallback() {
        when(textHandler.supportsMsgType()).thenReturn("text");
        when(textHandler.handle(any())).thenReturn("已收到");
        MessageDispatcher dispatcher = build();

        String reply = dispatcher.dispatch(message("video"));

        assertThat(reply).isEqualTo("已收到");
        verify(textHandler).handle(any());
        verify(anomalyEventService).record(captor.capture());
        AnomalyNotice notice = captor.getValue();
        assertThat(notice.layer()).isEqualTo(AnomalyLayer.L1);
        assertThat(notice.errorCode()).isEqualTo("UNKNOWN_MSG_TYPE");
        assertThat(notice.source()).isEqualTo("dispatcher");
        assertThat(notice.openid()).isEqualTo("openid-unknown-1");
        assertThat(notice.detail()).isEqualTo("type=video");
    }

    @Test
    @DisplayName("已注册类型：不产生埋点")
    void shouldNotRecordForKnownType() {
        when(textHandler.supportsMsgType()).thenReturn("text");
        when(textHandler.handle(any())).thenReturn("已收到");

        assertThat(build().dispatch(message("text"))).isEqualTo("已收到");

        verify(anomalyEventService, never()).record(any());
    }

    @Test
    @DisplayName("空白类型归一为 text 走默认处理器：属正常回落，不埋点")
    void shouldNotRecordBlankType() {
        when(textHandler.supportsMsgType()).thenReturn("text");
        when(textHandler.handle(any())).thenReturn("已收到");

        assertThat(build().dispatch(message("  "))).isEqualTo("已收到");

        verify(anomalyEventService, never()).record(any());
    }

    private MessageDispatcher build() {
        return new MessageDispatcher(List.of(textHandler), null, new DefaultFallbackService(),
                anomalyEventService);
    }

    private static InternalMessage message(String msgType) {
        return new InternalMessage("openid-unknown-1", msgType, "msg-1", "帮我查快递", null, null,
                null, System.currentTimeMillis() / 1000, Map.of());
    }
}
