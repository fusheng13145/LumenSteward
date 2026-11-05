package com.lumensteward.clawbot.application.dispatcher.handler;

import com.lumensteward.clawbot.application.orchestrator.AgentOrchestrator;
import com.lumensteward.clawbot.application.orchestrator.model.OrchestrationRequest;
import com.lumensteward.clawbot.application.orchestrator.model.OrchestrationResult;
import com.lumensteward.clawbot.infrastructure.client.wechat.model.InternalMessage;
import com.lumensteward.clawbot.infrastructure.persistence.support.SessionResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 链接消息处理器测试（迭代 4 W16）：合成消息交编排器、缺地址如实说明、standalone 兜底。
 */
class LinkMessageHandlerTest {

    @Test
    @DisplayName("supportsMsgType=link（分发器据此注册路由）")
    void shouldSupportLinkType() {
        assertThat(new LinkMessageHandler().supportsMsgType()).isEqualTo("link");
    }

    @Test
    @DisplayName("正常路径：标题与地址合成进用户消息交编排器（含 http 前缀触发 read_link 可见性）")
    void shouldRouteSynthesizedContentToOrchestrator() {
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        SessionResolver sessionResolver = mock(SessionResolver.class);
        when(sessionResolver.resolveOrCreate("openid-link-handler")).thenReturn(9L);
        when(orchestrator.run(any(OrchestrationRequest.class)))
                .thenReturn(new OrchestrationResult("（Mock）链接要点……", null, java.util.List.of(),
                        null, 1, 100));
        LinkMessageHandler handler = new LinkMessageHandler(orchestrator, sessionResolver);

        String reply = handler.handle(message("示例文章", "https://example.com/hello"));

        assertThat(reply).isEqualTo("（Mock）链接要点……");
        ArgumentCaptor<OrchestrationRequest> captor =
                ArgumentCaptor.forClass(OrchestrationRequest.class);
        org.mockito.Mockito.verify(orchestrator).run(captor.capture());
        OrchestrationRequest request = captor.getValue();
        assertThat(request.userMessage())
                .contains("《示例文章》")
                .contains("https://example.com/hello");
        assertThat(request.sessionId()).isEqualTo(9L);
    }

    @Test
    @DisplayName("缺地址：如实说明未附带地址，不伪造 URL（BR-04/BR-09）")
    void shouldStateMissingUrlHonestly() {
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        SessionResolver sessionResolver = mock(SessionResolver.class);
        when(sessionResolver.resolveOrCreate("openid-link-handler")).thenReturn(9L);
        when(orchestrator.run(any(OrchestrationRequest.class)))
                .thenReturn(new OrchestrationResult("ok", null, java.util.List.of(), null, 0, 10));
        LinkMessageHandler handler = new LinkMessageHandler(orchestrator, sessionResolver);

        handler.handle(message("无地址的分享", null));

        ArgumentCaptor<OrchestrationRequest> captor =
                ArgumentCaptor.forClass(OrchestrationRequest.class);
        org.mockito.Mockito.verify(orchestrator).run(captor.capture());
        assertThat(captor.getValue().userMessage()).contains("未附带地址").doesNotContain("https://");
    }

    @Test
    @DisplayName("standalone 构造：返回兜底回复，绝不 null、绝不抛异常（BR-04）")
    void shouldReturnFallbackWhenStandalone() {
        assertThat(new LinkMessageHandler().handle(message("标题", "https://example.com")))
                .isEqualTo(LinkMessageHandler.ORCHESTRATOR_UNAVAILABLE_REPLY);
        assertThat(new LinkMessageHandler().handle(null)).isNull();
    }

    private static InternalMessage message(String title, String url) {
        return new InternalMessage("openid-link-handler", "link", "msg-link-1", title,
                null, null, url, null, null, 0L, Map.of());
    }
}
