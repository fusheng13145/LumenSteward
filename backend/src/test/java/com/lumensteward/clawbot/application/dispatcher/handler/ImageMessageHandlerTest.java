package com.lumensteward.clawbot.application.dispatcher.handler;

import com.lumensteward.clawbot.application.dispatcher.handler.ImageMessageHandler;
import com.lumensteward.clawbot.application.orchestrator.AgentOrchestrator;
import com.lumensteward.clawbot.application.orchestrator.model.OrchestrationRequest;
import com.lumensteward.clawbot.application.orchestrator.model.OrchestrationResult;
import com.lumensteward.clawbot.common.enums.SessionState;
import com.lumensteward.clawbot.infrastructure.client.wechat.model.InternalMessage;
import com.lumensteward.clawbot.infrastructure.persistence.support.SessionResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 图片消息处理器测试（迭代 4 W11：占位 → 编排路由）。
 *
 * <p>验证图片消息合成为携带链接的用户消息进入对话引擎（识别由模型驱动的 recognize_image
 * 工具完成，处理器不臆造结果，BR-09），以及 standalone 构造的兜底语义。
 */
class ImageMessageHandlerTest {

    private static InternalMessage imageMessage(String picUrl) {
        return new InternalMessage("openid-img-handler", "image", "msg-img-1", null,
                "media-1", picUrl, null, null, 0L, Map.of());
    }

    @Test
    @DisplayName("路由：图片消息合成「（用户发来一张图片，链接：…）」进入编排器，返回终态回复")
    void routesToOrchestratorWithPicUrl() {
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        SessionResolver sessionResolver = mock(SessionResolver.class);
        when(sessionResolver.resolveOrCreate(any())).thenReturn(7L);
        when(orchestrator.run(any())).thenReturn(new OrchestrationResult(
                "（Mock）看过了，是一只橘猫。", SessionState.IDLE, List.of(), null, 1, 1));
        ImageMessageHandler handler = new ImageMessageHandler(orchestrator, sessionResolver);

        String reply = handler.handle(imageMessage("http://example.com/cat.jpg"));

        ArgumentCaptor<OrchestrationRequest> captor = ArgumentCaptor.forClass(OrchestrationRequest.class);
        verify(orchestrator).run(captor.capture());
        OrchestrationRequest request = captor.getValue();
        assertThat(request.openid()).isEqualTo("openid-img-handler");
        assertThat(request.sessionId()).isEqualTo(7L);
        assertThat(request.userMessage())
                .isEqualTo("（用户发来一张图片，链接：http://example.com/cat.jpg）");
        assertThat(reply).contains("橘猫");
    }

    @Test
    @DisplayName("无链接图片消息：如实说明未附带链接，不伪造 URL（BR-09）")
    void synthesizesHonestContentWhenPicUrlMissing() {
        AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
        when(orchestrator.run(any())).thenReturn(new OrchestrationResult(
                "请重新发送", SessionState.IDLE, List.of(), null, 0, 0));
        ImageMessageHandler handler = new ImageMessageHandler(orchestrator, mock(SessionResolver.class));

        handler.handle(imageMessage(null));

        ArgumentCaptor<OrchestrationRequest> captor = ArgumentCaptor.forClass(OrchestrationRequest.class);
        verify(orchestrator).run(captor.capture());
        assertThat(captor.getValue().userMessage()).isEqualTo("（用户发来一张图片，但未附带链接）");
    }

    @Test
    @DisplayName("standalone 构造（无编排器）：返回可读兜底文本，绝不返回 null、不抛异常")
    void standaloneConstructionReturnsFallback() {
        String reply = new ImageMessageHandler().handle(imageMessage("http://example.com/cat.jpg"));

        assertThat(reply).isEqualTo(ImageMessageHandler.ORCHESTRATOR_UNAVAILABLE_REPLY);
    }

    @Test
    @DisplayName("空消息返回 null（与文本处理器同契约）")
    void nullMessageReturnsNull() {
        assertThat(new ImageMessageHandler().handle(null)).isNull();
    }
}
