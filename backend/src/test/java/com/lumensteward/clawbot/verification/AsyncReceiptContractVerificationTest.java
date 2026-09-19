package com.lumensteward.clawbot.verification;

import com.lumensteward.clawbot.application.dispatcher.MessageDispatcher;
import com.lumensteward.clawbot.application.fallback.FallbackService;
import com.lumensteward.clawbot.application.wechat.WechatMessageService;
import com.lumensteward.clawbot.infrastructure.cache.DedupService;
import com.lumensteward.clawbot.infrastructure.cache.RateLimitDecision;
import com.lumensteward.clawbot.infrastructure.cache.RateLimitService;
import com.lumensteward.clawbot.infrastructure.client.wechat.WechatMessageParser;
import com.lumensteward.clawbot.infrastructure.client.wechat.WechatReplyBuilder;
import com.lumensteward.clawbot.infrastructure.client.wechat.WechatSignatureVerifier;
import com.lumensteward.clawbot.infrastructure.client.wechat.WechatTransport;
import com.lumensteward.clawbot.infrastructure.client.wechat.model.CustomerMessage;
import com.lumensteward.clawbot.infrastructure.client.wechat.model.InternalMessage;
import com.lumensteward.clawbot.infrastructure.client.wechat.model.SendResult;
import com.lumensteward.clawbot.infrastructure.persistence.entity.WxMessageEntity;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.WxSessionMapper;
import com.lumensteward.clawbot.infrastructure.persistence.repository.WxMessageRepository;
import com.lumensteward.clawbot.interfaces.wechat.WechatCallbackController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 独立验证：AC-A8「先回执后推送」（SRS FR-03 / BR-06）。
 *
 * <p>AC-A8 要求：Mock 处理链路上人为 sleep 8s 时，客户端应在 ≤1s 内收到占位回执，
 * 最终结果由客服消息<b>异步</b>推送（并落 {@code send_status}）。本测试以真实
 * {@link WechatCallbackController} + 真实 {@link WechatMessageService}、Mock 的慢分发链路，
 * 秒表实测三条必需行为：
 * <ol>
 *   <li>「先回执」：回调在 ≤1000ms 内返回占位回执（不阻塞至 8s 链路结束）；</li>
 *   <li>「后推送」：终态经客服消息<b>异步</b>推送（先占位、后终态两条）；</li>
 *   <li>「终态落库」：出站 {@code assistant} 行内容 == 终态、{@code send_status == 1}。</li>
 * </ol>
 *
 * <p>第 1 轮（修复前）本用例断言"回调 ≤1s"，实测被同步阻塞 8024ms 且从不推送客服消息 →
 * 失败，构成缺陷 D2 的反证。修复后（D2）三条断言全部通过。
 */
class AsyncReceiptContractVerificationTest {

    private static final long CHAIN_SLEEP_MS = 8000L;
    private static final long RECEIPT_DEADLINE_MS = 1000L;
    private static final long ASYNC_DEADLINE_MS = 9000L;
    private static final String FINAL_REPLY = "FINAL-REPLY";
    private static final String OPENID = "openid-qa-async-1234567890";

    @Test
    @DisplayName("AC-A8：链路 sleep 8s → 回调 ≤1s 返回占位回执，终态经客服消息异步推送并落库(send_status=1)")
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

        InternalMessage inbound = new InternalMessage(OPENID, "text",
                "msg-async-1", "帮我登记宠物", null, null, null, 0L, Map.of());
        when(parser.parse(any(), any())).thenReturn(inbound);
        when(dedup.markIfAbsent(any())).thenReturn(true);
        when(rateLimit.tryAcquire(any(), any())).thenReturn(RateLimitDecision.ALLOWED);
        // 客服消息推送成功（以便 pushFinal 落 send_status=1）
        when(transport.sendCustomerMessage(any(), any())).thenReturn(SendResult.ok(1L));
        // 模拟"重链路"：分发耗时 8s 后才产出终态文本
        when(dispatcher.dispatch(any())).thenAnswer(invocation -> {
            Thread.sleep(CHAIN_SLEEP_MS);
            return FINAL_REPLY;
        });

        WechatMessageService messageService = new WechatMessageService(
                transport, repository, replyBuilder, sessionMapper);
        WechatCallbackController controller = new WechatCallbackController(
                verifier, parser, dedup, rateLimit, dispatcher, messageService, fallback);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");

        ArgumentCaptor<CustomerMessage> pushed = ArgumentCaptor.forClass(CustomerMessage.class);
        long start = System.currentTimeMillis();
        String response = controller.receive("sig", "1", "n", null, "<xml/>", request);
        long elapsed = System.currentTimeMillis() - start;

        // 断言 1「先回执」：回调 ≤1s 内返回占位回执（修复前被同步阻塞 ~8s，故此处曾失败）
        assertThat(elapsed)
                .as("AC-A8：回调应在 ≤1000ms 内返回占位回执，实测 %dms", elapsed)
                .isLessThanOrEqualTo(RECEIPT_DEADLINE_MS);
        assertThat(response).isEqualTo(WechatMessageService.PLACEHOLDER_REPLY);

        // 断言 2「后推送」：终态经客服消息异步推送（先占位、后终态）
        verify(transport, timeout(ASYNC_DEADLINE_MS).atLeast(2))
                .sendCustomerMessage(any(), pushed.capture());
        List<String> texts = pushed.getAllValues().stream().map(CustomerMessage::content).toList();
        assertThat(texts)
                .as("应先推送占位回执、再异步推送终态（修复前从不推送，故此处曾失败）")
                .contains(WechatMessageService.PLACEHOLDER_REPLY)
                .contains(FINAL_REPLY);

        // 断言 3「终态落库」：出站 assistant 行内容 == 终态、send_status == 1
        ArgumentCaptor<WxMessageEntity> saved = ArgumentCaptor.forClass(WxMessageEntity.class);
        verify(repository, timeout(ASYNC_DEADLINE_MS).atLeast(2)).save(saved.capture());
        WxMessageEntity assistant = saved.getAllValues().stream()
                .filter(entity -> "assistant".equals(entity.getRole())).findFirst().orElse(null);
        assertThat(assistant).as("应异步落库 assistant 终态行").isNotNull();
        assertThat(assistant.getContent()).isEqualTo(FINAL_REPLY);
        assertThat(assistant.getSendStatus()).isEqualTo(1);
    }
}
