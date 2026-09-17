package com.lumensteward.clawbot.verification;

import com.lumensteward.clawbot.application.dispatcher.MessageDispatcher;
import com.lumensteward.clawbot.application.dispatcher.MessageHandler;
import com.lumensteward.clawbot.application.dispatcher.handler.ImageMessageHandler;
import com.lumensteward.clawbot.application.dispatcher.handler.TextMessageHandler;
import com.lumensteward.clawbot.application.dispatcher.handler.VoiceMessageHandler;
import com.lumensteward.clawbot.application.orchestrator.AgentOrchestrator;
import com.lumensteward.clawbot.application.orchestrator.model.OrchestrationRequest;
import com.lumensteward.clawbot.application.orchestrator.model.OrchestrationResult;
import com.lumensteward.clawbot.common.enums.SessionState;
import com.lumensteward.clawbot.infrastructure.client.wechat.model.InternalMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 独立验证：消息类型路由（SRS FR-02 / AC-A6 / AC-A9 / AC-A10）。
 *
 * <p>同时给出"文本处理器未接入对话引擎"的实证（见末条用例）——这是 QA 发现的关键缺陷之一。
 */
class MessageRoutingVerificationTest {

    /** 文本占位处理器（模拟"默认回落"目标，返回固定文本以便断言）。 */
    private static final class FakeTextHandler implements MessageHandler {
        @Override
        public String supportsMsgType() {
            return "text";
        }

        @Override
        public String handle(InternalMessage message) {
            return "TEXT-DEFAULT";
        }
    }

    private static MessageDispatcher dispatcher() {
        return new MessageDispatcher(List.of(
                new FakeTextHandler(), new ImageMessageHandler(), new VoiceMessageHandler()));
    }

    private static InternalMessage msg(String type) {
        return new InternalMessage("openid-routing-1234567890", type, "m1", "hi", null,
                null, null, 0L, Map.of());
    }

    @Test
    @DisplayName("AC-A6：未知类型 MsgType=foo 不抛异常，回落默认文本处理")
    void unknownTypeFallsBackToText() {
        MessageDispatcher dispatcher = dispatcher();

        assertThatCode(() -> dispatcher.dispatch(msg("foo"))).doesNotThrowAnyException();
        assertThat(dispatcher.dispatch(msg("foo"))).isEqualTo("TEXT-DEFAULT");
    }

    @Test
    @DisplayName("AC-A6：空消息不抛异常，返回 null")
    void nullMessageIgnored() {
        assertThatCode(() -> dispatcher().dispatch(null)).doesNotThrowAnyException();
        assertThat(dispatcher().dispatch(null)).isNull();
    }

    @Test
    @DisplayName("AC-A9：image 消息返回如实提示，非空且不含异常")
    void imagePlaceholder() {
        String reply = dispatcher().dispatch(msg("image"));
        assertThat(reply).isNotBlank();
    }

    @Test
    @DisplayName("AC-A10：voice 消息返回如实提示，非空且不含异常")
    void voicePlaceholder() {
        String reply = dispatcher().dispatch(msg("voice"));
        assertThat(reply).isNotBlank();
    }

    @Test
    @DisplayName("AC-A7：文本消息须真正委派对话引擎（AgentOrchestrator#run 被调用且终态作为回复返回）")
    void textMessageMustEnterDialogueEngine() {
        // 记录调用入参的编排器替身：若文本消息未触达对话引擎，captured 将保持 null
        AtomicReference<OrchestrationRequest> captured = new AtomicReference<>();
        AgentOrchestrator recordingOrchestrator = request -> {
            captured.set(request);
            return new OrchestrationResult("已登记宠物小光", SessionState.TASKING, List.of(), null, 1, 1);
        };
        // 真实 TextMessageHandler，但注入可记录的编排器（证明"委派"而非"返回兜底文案"）
        TextMessageHandler real = new TextMessageHandler(recordingOrchestrator, null);
        MessageDispatcher dispatcher = new MessageDispatcher(
                List.of(real, new ImageMessageHandler(), new VoiceMessageHandler()));

        String reply = dispatcher.dispatch(msg("text"));

        // 断言 1：文本消息确实触达对话引擎，且入参报文一致（userMessage = 入站内容）
        assertThat(captured.get())
                .as("文本消息必须真正调用 AgentOrchestrator#run（不得以兜底文案冒充）")
                .isNotNull();
        assertThat(captured.get().userMessage()).isEqualTo("hi");
        // 断言 2：对话引擎终态被作为业务回复返回
        assertThat(reply).isEqualTo("已登记宠物小光");
    }
}
