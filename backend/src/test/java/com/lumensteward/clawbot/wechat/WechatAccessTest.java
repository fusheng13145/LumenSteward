package com.lumensteward.clawbot.wechat;

import com.lumensteward.clawbot.application.dispatcher.MessageDispatcher;
import com.lumensteward.clawbot.application.dispatcher.MessageHandler;
import com.lumensteward.clawbot.application.dispatcher.handler.EventMessageHandler;
import com.lumensteward.clawbot.application.dispatcher.handler.ImageMessageHandler;
import com.lumensteward.clawbot.application.dispatcher.handler.LocationMessageHandler;
import com.lumensteward.clawbot.application.dispatcher.handler.VoiceMessageHandler;
import com.lumensteward.clawbot.application.fallback.DefaultFallbackService;
import com.lumensteward.clawbot.application.wechat.WechatMessageService;
import com.lumensteward.clawbot.common.api.PageQuery;
import com.lumensteward.clawbot.common.api.PageResult;
import com.lumensteward.clawbot.common.exception.SignatureInvalidException;
import com.lumensteward.clawbot.common.exception.TimestampOutOfWindowException;
import com.lumensteward.clawbot.infrastructure.cache.DedupService;
import com.lumensteward.clawbot.infrastructure.cache.RateLimitService;
import com.lumensteward.clawbot.infrastructure.client.wechat.MockWechatTransport;
import com.lumensteward.clawbot.infrastructure.client.wechat.WechatMessageParserImpl;
import com.lumensteward.clawbot.infrastructure.client.wechat.WechatReplyBuilderImpl;
import com.lumensteward.clawbot.infrastructure.client.wechat.WechatSignatureVerifierImpl;
import com.lumensteward.clawbot.infrastructure.client.wechat.model.InternalMessage;
import com.lumensteward.clawbot.infrastructure.config.properties.WechatProperties;
import com.lumensteward.clawbot.infrastructure.persistence.entity.WxMessageEntity;
import com.lumensteward.clawbot.infrastructure.persistence.repository.WxMessageRepository;
import com.lumensteward.clawbot.interfaces.wechat.WechatCallbackController;
import com.lumensteward.clawbot.support.WechatSignatureGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 微信接入层测试（AC-A1~A6 / AC-A9~A10）。
 *
 * <p>覆盖：验签（正确/错误/时间窗 ±）、MsgId 幂等去重（10 次处理 1 次）、GET echostr 回显、
 * 类型路由（含未知类型不 500）、image/voice 如实提示、正确签名 POST 落库 1 条。
 */
class WechatAccessTest {

    private static final String TOKEN = "test-token";
    private static final long WINDOW = 300L;

    private WechatProperties properties;
    private WechatSignatureVerifierImpl verifier;
    private WechatMessageParserImpl parser;
    private InMemoryMessageRepository repository;
    private InMemoryDedupService dedupService;
    private CountingTextHandler textHandler;
    private WechatMessageService messageService;
    private WechatCallbackController controller;

    @BeforeEach
    void setUp() {
        properties = new WechatProperties(TOKEN, "wxapp", "secret", "", true, (int) WINDOW, 300);
        verifier = new WechatSignatureVerifierImpl(properties);
        parser = new WechatMessageParserImpl();
        repository = new InMemoryMessageRepository();
        dedupService = new InMemoryDedupService();
        textHandler = new CountingTextHandler();

        List<MessageHandler> handlers = List.of(textHandler, new ImageMessageHandler(),
                new VoiceMessageHandler(), new LocationMessageHandler(), new EventMessageHandler());
        MessageDispatcher dispatcher = new MessageDispatcher(handlers);

        messageService = new WechatMessageService(new MockWechatTransport(), repository,
                new WechatReplyBuilderImpl(properties), null);

        controller = new WechatCallbackController(verifier, parser, dedupService, new AllowAllRateLimit(),
                dispatcher, messageService, new DefaultFallbackService());
    }

    @Test
    @DisplayName("① 正确签名 GET 原样回显 echostr（AC-A4）")
    void getShouldEchoEchostr() {
        String timestamp = WechatSignatureGenerator.nowTimestamp();
        String nonce = "abc";
        String signature = WechatSignatureGenerator.sign(TOKEN, timestamp, nonce);

        String result = controller.verify(signature, timestamp, nonce, "hello-echostr");
        assertThat(result).isEqualTo("hello-echostr");
    }

    @Test
    @DisplayName("① 非法签名 100 次全部被拒且业务调用 0 次（AC-A2）")
    void invalidSignatureShouldRejectAndNotInvokeBusiness() {
        String timestamp = WechatSignatureGenerator.nowTimestamp();
        for (int i = 0; i < 100; i++) {
            final String nonce = "n" + i;
            final String badSignature = WechatSignatureGenerator.wrongSignature();
            assertThatThrownBy(() -> controller.verify(badSignature, timestamp, nonce, "echo"))
                    .isInstanceOf(SignatureInvalidException.class);
        }
        assertThat(textHandler.count.get()).isZero();
    }

    @Test
    @DisplayName("① 时间窗 ±300s：越界拒绝，边界内通过（AC-A5）")
    void timeWindowShouldRejectOutOfRange() {
        String nonce = "n1";
        String outSignature = WechatSignatureGenerator.sign(TOKEN,
                WechatSignatureGenerator.timestampOffset(WINDOW + 60), nonce);
        assertThatThrownBy(() -> controller.verify(outSignature,
                WechatSignatureGenerator.timestampOffset(WINDOW + 60), nonce, "e"))
                .isInstanceOf(TimestampOutOfWindowException.class);

        String inSig = WechatSignatureGenerator.sign(TOKEN,
                WechatSignatureGenerator.timestampOffset(-120), nonce);
        assertThat(controller.verify(inSig, WechatSignatureGenerator.timestampOffset(-120), nonce, "ok"))
                .isEqualTo("ok");
    }

    @Test
    @DisplayName("① 同 MsgId 10 次，业务仅处理 1 次（AC-A3）")
    void duplicateMsgIdShouldBeHandledOnce() {
        String body = textMessageXml("msg-dup-1", "小光", "你好呀");
        for (int i = 0; i < 10; i++) {
            String result = post(body, "text");
            assertThat(result).isNotBlank();
        }
        assertThat(textHandler.count.get()).isEqualTo(1);
        assertThat(repository.saved).hasSize(1);
    }

    @Test
    @DisplayName("② 未知消息类型不 500，回落默认文本处理（AC-A6）")
    void unknownMsgTypeShouldNotFail() {
        String body = "<xml><FromUserName><![CDATA[u-1]]></FromUserName>"
                + "<MsgType><![CDATA[foo]]></MsgType><MsgId>m-1</MsgId>"
                + "<Content><![CDATA[whatever]]></Content></xml>";
        String result = post(body, "foo");
        assertThat(result).isEqualTo("success");
        assertThat(textHandler.count.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("② image/voice 返回如实提示且不外呼（AC-A9/A10）")
    void imageAndVoiceShouldReturnHonestPrompt() {
        String imageBody = "<xml><FromUserName><![CDATA[u-2]]></FromUserName>"
                + "<MsgType><![CDATA[image]]></MsgType><MsgId>m-img</MsgId>"
                + "<MediaId><![CDATA[media-1]]></MediaId></xml>";
        String imageResult = post(imageBody, "image");
        assertThat(imageResult).contains("识别图片");

        String voiceBody = "<xml><FromUserName><![CDATA[u-3]]></FromUserName>"
                + "<MsgType><![CDATA[voice]]></MsgType><MsgId>m-voice</MsgId>"
                + "<MediaId><![CDATA[media-2]]></MediaId></xml>";
        String voiceResult = post(voiceBody, "voice");
        assertThat(voiceResult).contains("听懂语音");

        // 图片/语音不得进入文本处理链路
        assertThat(textHandler.count.get()).isZero();
    }

    @Test
    @DisplayName("正确签名 POST 文本消息落库 1 条（AC-A1）")
    void validPostShouldPersistOnce() {
        String body = textMessageXml("m-persist", "小光", "帮我记一下");
        String result = post(body, "text");
        assertThat(result).isEqualTo("success");
        assertThat(repository.saved).hasSize(1);
        assertThat(repository.saved.get(0).getRole()).isEqualTo("user");
        assertThat(repository.saved.get(0).getMsgType()).isEqualTo("text");
    }

    private String post(String body, String msgTypeHint) {
        String timestamp = WechatSignatureGenerator.nowTimestamp();
        String nonce = "nonce";
        String signature = WechatSignatureGenerator.sign(TOKEN, timestamp, nonce);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.setParameter("msg_type", msgTypeHint);
        return controller.receive(signature, timestamp, nonce, null, body, request);
    }

    private static String textMessageXml(String msgId, String openid, String content) {
        return "<xml><FromUserName><![CDATA[" + openid + "]]></FromUserName>"
                + "<MsgType><![CDATA[text]]></MsgType>"
                + "<MsgId>" + msgId + "</MsgId>"
                + "<Content><![CDATA[" + content + "]]></Content></xml>";
    }

    /** 计数文本处理器（T03 文本处理返回 null，仅统计调用次数）。 */
    static class CountingTextHandler implements MessageHandler {
        final AtomicInteger count = new AtomicInteger();

        @Override
        public String supportsMsgType() {
            return "text";
        }

        @Override
        public String handle(InternalMessage message) {
            count.incrementAndGet();
            return null;
        }
    }

    /** 内存去重服务。 */
    static class InMemoryDedupService implements DedupService {
        private final Set<String> seen = new HashSet<>();

        @Override
        public boolean markIfAbsent(String msgId) {
            if (msgId == null || msgId.isBlank()) {
                return true;
            }
            return seen.add(msgId);
        }
    }

    /** 放行限流。 */
    static class AllowAllRateLimit implements RateLimitService {
        @Override
        public boolean tryAcquire(String openid, String ip) {
            return true;
        }
    }

    /** 内存消息仓库（捕获落库记录）。 */
    static class InMemoryMessageRepository implements WxMessageRepository {
        final List<WxMessageEntity> saved = new ArrayList<>();

        @Override
        public void save(WxMessageEntity entity) {
            saved.add(entity);
        }

        @Override
        public PageResult<WxMessageEntity> page(PageQuery query, String openid, String role) {
            return PageResult.of(saved, saved.size(), 1, 20);
        }
    }
}
