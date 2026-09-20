package com.lumensteward.clawbot.wechat;

import com.lumensteward.clawbot.application.anomaly.AnomalyNotice;
import com.lumensteward.clawbot.application.dispatcher.MessageDispatcher;
import com.lumensteward.clawbot.application.fallback.DefaultFallbackService;
import com.lumensteward.clawbot.application.wechat.WechatMessageService;
import com.lumensteward.clawbot.common.enums.AnomalyLayer;
import com.lumensteward.clawbot.common.exception.SignatureInvalidException;
import com.lumensteward.clawbot.common.exception.TimestampOutOfWindowException;
import com.lumensteward.clawbot.infrastructure.cache.DedupService;
import com.lumensteward.clawbot.infrastructure.cache.RateLimitDecision;
import com.lumensteward.clawbot.infrastructure.cache.RateLimitService;
import com.lumensteward.clawbot.infrastructure.client.wechat.WechatMessageParser;
import com.lumensteward.clawbot.infrastructure.client.wechat.WechatSignatureVerifier;
import com.lumensteward.clawbot.infrastructure.client.wechat.model.InternalMessage;
import com.lumensteward.clawbot.infrastructure.persistence.service.AnomalyEventService;
import com.lumensteward.clawbot.interfaces.wechat.WechatCallbackController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 接入层（L1）异常埋点测试（SRS 2.3.5 L1 / 迭代 4 W1）。
 *
 * <p>判据：验签失败、时间戳越界、幂等重复三类 L1 判据必须<b>既照原样拒绝</b>（异常仍冒泡给
 * {@code GlobalExceptionHandler}，行为不变），<b>又留下一条可查事实</b>（{@code log_anomaly_event}
 * 层=L1）；正常放行时不得产生埋点。
 */
class WechatCallbackAnomalyTest {

    private final WechatSignatureVerifier signatureVerifier = mock(WechatSignatureVerifier.class);
    private final WechatMessageParser messageParser = mock(WechatMessageParser.class);
    private final DedupService dedupService = mock(DedupService.class);
    private final RateLimitService rateLimitService = mock(RateLimitService.class);
    private final MessageDispatcher messageDispatcher = mock(MessageDispatcher.class);
    private final WechatMessageService wechatMessageService = mock(WechatMessageService.class);
    private final AnomalyEventService anomalyEventService = mock(AnomalyEventService.class);
    private final ArgumentCaptor<AnomalyNotice> captor = ArgumentCaptor.forClass(AnomalyNotice.class);

    private WechatCallbackController controller;

    private void setUpController() {
        controller = new WechatCallbackController(signatureVerifier, messageParser, dedupService,
                rateLimitService, messageDispatcher, wechatMessageService,
                new DefaultFallbackService(), anomalyEventService);
    }

    @Test
    @DisplayName("签名不一致：埋 L1 SIGNATURE_INVALID 且异常原样抛出（不改变拒绝行为）")
    void shouldRecordSignatureInvalid() {
        setUpController();
        doThrow(new SignatureInvalidException("微信签名校验失败"))
                .when(signatureVerifier).verify(any(), any(), any(), any());

        assertThatThrownBy(() -> controller.verify("bad", "1700000000", "n1", "echo"))
                .isInstanceOf(SignatureInvalidException.class);

        verify(anomalyEventService).record(captor.capture());
        AnomalyNotice notice = captor.getValue();
        assertThat(notice.layer()).isEqualTo(AnomalyLayer.L1);
        assertThat(notice.errorCode()).isEqualTo("SIGNATURE_INVALID");
        assertThat(notice.source()).isEqualTo("wechat.callback");
        assertThat(notice.openid()).isNull();
    }

    @Test
    @DisplayName("时间戳越界：埋 L1 TIMESTAMP_OUT_OF_WINDOW")
    void shouldRecordTimestampOutOfWindow() {
        setUpController();
        doThrow(new TimestampOutOfWindowException("请求时间戳超出允许窗口"))
                .when(signatureVerifier).verify(any(), any(), any(), any());

        assertThatThrownBy(() -> controller.receive("sig", "1700000000", "n1", null, "<xml/>", null))
                .isInstanceOf(TimestampOutOfWindowException.class);

        verify(anomalyEventService).record(captor.capture());
        assertThat(captor.getValue().errorCode()).isEqualTo("TIMESTAMP_OUT_OF_WINDOW");
        assertThat(captor.getValue().layer()).isEqualTo(AnomalyLayer.L1);
    }

    @Test
    @DisplayName("重复消息：埋 L1 MSG_DUPLICATED，携带原始 openid（脱敏由落库侧统一完成）")
    void shouldRecordDuplicatedMessage() {
        setUpController();
        when(messageParser.parse(any(), any())).thenReturn(message("openid-dup-user"));
        when(dedupService.markIfAbsent("msg-1")).thenReturn(false);

        controller.receive("sig", "1700000000", "n1", null, "<xml/>", new MockHttpServletRequest());

        verify(anomalyEventService).record(captor.capture());
        AnomalyNotice notice = captor.getValue();
        assertThat(notice.layer()).isEqualTo(AnomalyLayer.L1);
        assertThat(notice.errorCode()).isEqualTo("MSG_DUPLICATED");
        assertThat(notice.openid()).isEqualTo("openid-dup-user");
        assertThat(notice.detail()).isEqualTo("msgId=msg-1");
        // 重复消息不再进入分发
        verify(messageDispatcher, never()).dispatch(any());
    }

    @Test
    @DisplayName("验签通过且非重复：不产生 L1 埋点")
    void shouldNotRecordOnHappyPath() {
        setUpController();
        when(messageParser.parse(any(), any())).thenReturn(message("openid-ok-user"));
        when(dedupService.markIfAbsent("msg-1")).thenReturn(true);
        when(rateLimitService.tryAcquire(any(), any())).thenReturn(RateLimitDecision.ALLOWED);
        when(messageDispatcher.dispatch(any())).thenReturn("已帮你查询");
        when(wechatMessageService.handleInboundWithReceipt(any(), any())).thenReturn("success");

        assertThat(controller.receive("sig", "1700000000", "n1", null, "<xml/>",
                new MockHttpServletRequest())).isEqualTo("success");

        verify(anomalyEventService, never()).record(any());
    }

    @Test
    @DisplayName("未注入埋点服务（独立构造）：拒绝行为不变，不因缺失服务而报错")
    void shouldStillRejectWithoutAnomalyService() {
        controller = new WechatCallbackController(signatureVerifier, messageParser, dedupService,
                rateLimitService, messageDispatcher, wechatMessageService,
                new DefaultFallbackService(), null);
        doThrow(new SignatureInvalidException("微信签名校验失败"))
                .when(signatureVerifier).verify(any(), any(), any(), any());

        assertThatThrownBy(() -> controller.verify("bad", "1700000000", "n1", "echo"))
                .isInstanceOf(SignatureInvalidException.class);
    }

    private static InternalMessage message(String openid) {
        return new InternalMessage(openid, "text", "msg-1", "帮我查快递", null, null, null,
                System.currentTimeMillis() / 1000, Map.of());
    }
}
