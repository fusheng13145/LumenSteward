package com.lumensteward.clawbot.verification;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.lumensteward.clawbot.application.dispatcher.MessageDispatcher;
import com.lumensteward.clawbot.application.dispatcher.handler.EventMessageHandler;
import com.lumensteward.clawbot.application.dispatcher.handler.ImageMessageHandler;
import com.lumensteward.clawbot.application.dispatcher.handler.LocationMessageHandler;
import com.lumensteward.clawbot.application.dispatcher.handler.TextMessageHandler;
import com.lumensteward.clawbot.application.dispatcher.handler.VoiceMessageHandler;
import com.lumensteward.clawbot.application.fallback.DefaultFallbackService;
import com.lumensteward.clawbot.application.orchestrator.AgentOrchestrator;
import com.lumensteward.clawbot.application.orchestrator.model.OrchestrationRequest;
import com.lumensteward.clawbot.application.orchestrator.model.OrchestrationResult;
import com.lumensteward.clawbot.application.wechat.WechatMessageService;
import com.lumensteward.clawbot.common.api.PageQuery;
import com.lumensteward.clawbot.common.api.PageResult;
import com.lumensteward.clawbot.common.enums.SessionState;
import com.lumensteward.clawbot.infrastructure.cache.DedupService;
import com.lumensteward.clawbot.infrastructure.cache.RateLimitDecision;
import com.lumensteward.clawbot.infrastructure.cache.RateLimitService;
import com.lumensteward.clawbot.infrastructure.client.wechat.WechatMessageParserImpl;
import com.lumensteward.clawbot.infrastructure.client.wechat.WechatReplyBuilderImpl;
import com.lumensteward.clawbot.infrastructure.client.wechat.WechatSignatureVerifierImpl;
import com.lumensteward.clawbot.infrastructure.client.wechat.WechatTransport;
import com.lumensteward.clawbot.infrastructure.config.properties.WechatProperties;
import com.lumensteward.clawbot.infrastructure.persistence.entity.WxMessageEntity;
import com.lumensteward.clawbot.infrastructure.persistence.entity.WxSessionEntity;
import com.lumensteward.clawbot.infrastructure.persistence.entity.WxUserEntity;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.WxSessionMapper;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.WxUserMapper;
import com.lumensteward.clawbot.infrastructure.persistence.repository.WxMessageRepository;
import com.lumensteward.clawbot.infrastructure.persistence.support.SessionResolver;
import com.lumensteward.clawbot.interfaces.wechat.WechatCallbackController;
import com.lumensteward.clawbot.support.WechatSignatureGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 独立验证：主链路「微信回调入口 → 类型路由 → 对话引擎（AgentOrchestrator）→ 终态回复落库」
 * 的端到端接线（D1 修复证据 / AC-A7 / AC-A1 / AC-E5）。
 *
 * <p><b>为什么需要本用例（而非仅只读 QA 的 {@code MessageRoutingVerificationTest}）：</b>
 * 后者以无参构造 {@code new TextMessageHandler()} 断言"非空"——无参构造在无编排器时返回兜底文案，
 * 并不能证明<b>真实链路上编排器被调用</b>。本用例以<b>真实</b> {@link WechatCallbackController} +
 * 真实 {@link MessageDispatcher} + 真实 {@link TextMessageHandler}（注入真实 {@link SessionResolver}
 * 与可记录调用的 {@link AgentOrchestrator}），走<b>真实签名</b> POST 全链路，断言：
 * <ol>
 *   <li>回调入口<b>确实调用了</b> {@link AgentOrchestrator#run(OrchestrationRequest)}，且入参
 *       （openid / userMessage / sessionId）与入站报文一致；</li>
 *   <li>编排器终态文本被作为被动回执产出；</li>
 *   <li>{@code wx_message} 中同时落<b>入站(user)</b>与<b>出站(assistant)</b>两行，assistant 内容
 *       等于编排器终态、{@code send_status=1}；</li>
 *   <li>首交互用户触发 {@code wx_user} upsert（AC-E5）。</li>
 * </ol>
 *
 * <p>全部以内存/替身实现，不依赖 MySQL / Docker，可独立执行（组件级跑通；含真实 DB 行的集成证据
 * 见 {@code e2e/MainChainRowEvidenceIT}，本环境 MySQL 凭据不可用故标注未实跑）。
 */
class MainChainWiringVerificationTest {

    private static final String TOKEN = "test-token";
    private static final String OPENID = "openid-mainchain-123456";
    private static final String SESSION_ID = "42";
    private static final String FINAL_REPLY = "已经帮你把小光的档案登记好了。";

    @Test
    @DisplayName("D1/AC-A7：回调入口 → 编排器 → 终态回复落库（真实全链路）")
    void callbackEntryReachesOrchestratorAndPersistsAssistantReply() throws Exception {
        WechatProperties properties = new WechatProperties(TOKEN, "wxapp", "secret", "", true, 300, 300);
        WechatSignatureVerifierImpl verifier = new WechatSignatureVerifierImpl(properties);
        WechatMessageParserImpl parser = new WechatMessageParserImpl();

        CapturingMessageRepository repository = new CapturingMessageRepository();
        WechatTransport transport = mock(WechatTransport.class);

        // 会话解析：命已存在会话（id=42），验证 sessionId 被正确带入编排请求
        WxSessionMapper sessionMapper = mock(WxSessionMapper.class);
        WxSessionEntity session = new WxSessionEntity();
        session.setId(42L);
        session.setOpenid(OPENID);
        when(sessionMapper.selectOne(anyWrapper())).thenReturn(session);
        SessionResolver sessionResolver = new SessionResolver(sessionMapper);

        // 首交互用户：不存在 → 应 insert 一行（AC-E5）
        WxUserMapper userMapper = mock(WxUserMapper.class);
        when(userMapper.selectOne(anyUserWrapper())).thenReturn(null);

        WechatMessageService messageService = new WechatMessageService(transport, repository,
                new WechatReplyBuilderImpl(properties), sessionMapper, userMapper);

        RecordingOrchestrator orchestrator = new RecordingOrchestrator(FINAL_REPLY);
        TextMessageHandler textHandler = new TextMessageHandler(orchestrator, sessionResolver);
        MessageDispatcher dispatcher = new MessageDispatcher(List.of(
                textHandler, new ImageMessageHandler(), new VoiceMessageHandler(),
                new LocationMessageHandler(), new EventMessageHandler()));

        DedupService dedup = mock(DedupService.class);
        when(dedup.markIfAbsent(any())).thenReturn(true);
        RateLimitService rateLimit = mock(RateLimitService.class);
        when(rateLimit.tryAcquire(any(), any())).thenReturn(RateLimitDecision.ALLOWED);

        WechatCallbackController controller = new WechatCallbackController(verifier, parser, dedup, rateLimit,
                dispatcher, messageService, new DefaultFallbackService(), null);

        // 真实签名 + 真实报文（单一入口：Mock/Real 共用同一段验签）
        String timestamp = WechatSignatureGenerator.nowTimestamp();
        String nonce = "nonce";
        String signature = WechatSignatureGenerator.sign(TOKEN, timestamp, nonce);
        String body = "<xml><FromUserName><![CDATA[" + OPENID + "]]></FromUserName>"
                + "<MsgType><![CDATA[text]]></MsgType>"
                + "<MsgId>m-mainchain-1</MsgId>"
                + "<Content><![CDATA[帮我登记宠物小光]]></Content></xml>";
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.setParameter("msg_type", "text");

        // act：一次回调即触发全链路
        String response = controller.receive(signature, timestamp, nonce, null, body, request);

        // 断言 1：回调入口真的进到了编排器（而非"空 handler 返回兜底"的假绿）
        OrchestrationRequest captured = orchestrator.captured.get();
        assertThat(captured).as("回调入口必须真正调用 AgentOrchestrator#run").isNotNull();
        assertThat(captured.openid()).isEqualTo(OPENID);
        assertThat(captured.userMessage()).isEqualTo("帮我登记宠物小光");
        assertThat(captured.sessionId()).isEqualTo(42L);

        // 断言 2：终态回复被作为被动回执产出
        assertThat(response).isNotBlank();

        // 断言 3：入站(user) + 出站(assistant) 两行均落库，assistant 内容 == 编排器终态
        assertThat(repository.saved).extracting(WxMessageEntity::getRole).contains("user", "assistant");
        WxMessageEntity assistant = repository.saved.stream()
                .filter(entity -> "assistant".equals(entity.getRole())).findFirst().orElseThrow();
        assertThat(assistant.getContent()).isEqualTo(FINAL_REPLY);
        assertThat(assistant.getSendStatus()).isEqualTo(1);

        // 断言 4：首交互用户 upsert（AC-E5）
        verify(userMapper).insert(any(WxUserEntity.class));
    }

    @SuppressWarnings("unchecked")
    private static Wrapper<WxSessionEntity> anyWrapper() {
        return any();
    }

    @SuppressWarnings("unchecked")
    private static Wrapper<WxUserEntity> anyUserWrapper() {
        return any();
    }

    /** 可记录调用的编排器替身：证明"处理器确实委托给对话引擎"。 */
    private static final class RecordingOrchestrator implements AgentOrchestrator {
        private final AtomicReference<OrchestrationRequest> captured = new AtomicReference<>();
        private final String replyText;

        private RecordingOrchestrator(String replyText) {
            this.replyText = replyText;
        }

        @Override
        public OrchestrationResult run(OrchestrationRequest request) {
            captured.set(request);
            return new OrchestrationResult(replyText, SessionState.TASKING, List.of(), null, 1, 1);
        }
    }

    /** 内存消息仓库（捕获落库记录）。 */
    private static final class CapturingMessageRepository implements WxMessageRepository {
        private final List<WxMessageEntity> saved = new ArrayList<>();

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
