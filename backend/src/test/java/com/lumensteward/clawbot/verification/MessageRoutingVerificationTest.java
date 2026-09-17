package com.lumensteward.clawbot.verification;

import com.lumensteward.clawbot.application.dispatcher.MessageDispatcher;
import com.lumensteward.clawbot.application.dispatcher.MessageHandler;
import com.lumensteward.clawbot.application.dispatcher.handler.ImageMessageHandler;
import com.lumensteward.clawbot.application.dispatcher.handler.TextMessageHandler;
import com.lumensteward.clawbot.application.dispatcher.handler.VoiceMessageHandler;
import com.lumensteward.clawbot.infrastructure.client.wechat.model.InternalMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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
    @DisplayName("AC-A7【要求】文本消息须进入对话引擎并产出业务回复（当前恒返回 null → 失败即缺陷证据）")
    void textMessageMustEnterDialogueEngine() {
        // 真实 TextMessageHandler（应用运行期实际装配的 Bean）
        TextMessageHandler real = new TextMessageHandler();
        MessageDispatcher dispatcher = new MessageDispatcher(
                List.of(real, new ImageMessageHandler(), new VoiceMessageHandler()));

        String reply = dispatcher.dispatch(msg("text"));

        // 期望：进入对话引擎后产出终态回复；实测为 null，说明未接入 AgentOrchestrator
        assertThat(reply)
                .as("文本消息应产出业务回复（进入对话引擎）；实测为 null 说明主链路未接线")
                .isNotNull()
                .isNotBlank();
    }
}
