package com.lumensteward.clawbot.verification;

import com.lumensteward.clawbot.application.dispatcher.MessageDispatcher;
import com.lumensteward.clawbot.application.fallback.FallbackService;
import com.lumensteward.clawbot.application.wechat.WechatMessageService;
import com.lumensteward.clawbot.infrastructure.cache.DedupService;
import com.lumensteward.clawbot.infrastructure.cache.RateLimitService;
import com.lumensteward.clawbot.infrastructure.client.wechat.WechatMessageParser;
import com.lumensteward.clawbot.infrastructure.client.wechat.WechatReplyBuilder;
import com.lumensteward.clawbot.infrastructure.client.wechat.WechatSignatureVerifier;
import com.lumensteward.clawbot.infrastructure.client.wechat.WechatTransport;
import com.lumensteward.clawbot.infrastructure.client.wechat.model.InternalMessage;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.WxSessionMapper;
import com.lumensteward.clawbot.infrastructure.persistence.repository.WxMessageRepository;
import com.lumensteward.clawbot.interfaces.wechat.WechatCallbackController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 独立验证：AC-A8「先回执后推送」（SRS FR-03 / BR-06）。
 *
 * <p>AC-A8 要求：Mock 处理链路上人为 sleep 8s 时，客户端应在 ≤1s 内收到占位回执，
 * 最终结果由客服消息<b>异步</b>推送。本测试以真实 {@link WechatCallbackController} +
 * 真实 {@link WechatMessageService} 实测：回调是否会被同步阻塞到链路结束。
 *
 * <p>本测试断言 <b>AC-A8 的必需行为</b>（≤1s 回执 + 客服消息异步推送）。当前实现为
 * 同步阻塞并直接返回终态、且 {@code pushAsync} 为死代码（无任何调用点），
 * 因此本用例<b>应当失败</b>——失败即为缺陷证据（AC-A8 未落地）。
 */
class AsyncReceiptContractVerificationTest {

    private static final long CHAIN_SLEEP_MS = 8000L;

    @Test
    @DisplayName("AC-A8【要求】链路 sleep 8s，回调须 ≤1s 返回占位回执并经客服消息异步推送终态")
    void shouldReturnEarlyReceiptAndPushAsync() {
        WechatSignatureVerifier verifier = mock(WechatSignatureVerifier.class);
        WechatMessageParser parser = mock(WechatMessageParser.class);
        DedupService dedup = mock(DedupService.class);
        RateLimitService rateLimit = mock(RateLimitService.class);
        MessageDispatcher dispatcher = mock(MessageDispatcher.class);
        WechatTransport transport = mock(WechatTransport.class);
        WxMessageRepository repository = mock(WxMessageRepository.class);
        WechatReplyBuilder replyBuilder = mock(WechatReplyBuilder.class);
        WxSessionMapper sessionMapper = mock(WxSessionMapper.class);
        FallbackService fallback = mock(FallbackService.class);

        InternalMessage inbound = new InternalMessage("openid-qa-async-1234567890", "text",
                "msg-async-1", "帮我登记宠物", null, null, null, 0L, Map.of());
        when(parser.parse(any(), any())).thenReturn(inbound);
        when(dedup.markIfAbsent(any())).thenReturn(true);
        when(rateLimit.tryAcquire(any(), any())).thenReturn(true);
        when(replyBuilder.buildTextReply(any(), any())).thenReturn("<xml>REPLY</xml>");
        // 模拟"重链路"：分发耗时 8s 后才产出终态文本
        when(dispatcher.dispatch(any())).thenAnswer(invocation -> {
            Thread.sleep(CHAIN_SLEEP_MS);
            return "FINAL-REPLY";
        });

        WechatMessageService messageService = new WechatMessageService(
                transport, repository, replyBuilder, sessionMapper);
        WechatCallbackController controller = new WechatCallbackController(
                verifier, parser, dedup, rateLimit, dispatcher, messageService, fallback);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");

        long start = System.currentTimeMillis();
        String response = controller.receive("sig", "1", "n", null, "<xml/>", request);
        long elapsed = System.currentTimeMillis() - start;

        // 断言 AC-A8 的"必需行为"：≤1s 内返回占位回执（当前实现同步阻塞 ~8s，故本断言失败）
        assertThat(elapsed)
                .as("AC-A8：回调应在 ≤1000ms 内返回占位回执，实测 %dms（被同步阻塞至链路结束）", elapsed)
                .isLessThanOrEqualTo(1000L);
        // 断言终态由客服消息异步推送（当前实现从不调用，故本断言失败）
        verify(transport, org.mockito.Mockito.atLeastOnce())
                .sendCustomerMessage(any(), any());
        assertThat(response).isNotBlank();
    }
}
