package com.lumensteward.clawbot.application.dispatcher.handler;

import com.lumensteward.clawbot.application.config.DynamicConfigService;
import com.lumensteward.clawbot.application.orchestrator.AgentOrchestrator;
import com.lumensteward.clawbot.application.orchestrator.model.OrchestrationRequest;
import com.lumensteward.clawbot.application.orchestrator.model.OrchestrationResult;
import com.lumensteward.clawbot.common.enums.SessionState;
import com.lumensteward.clawbot.domain.tool.ToolRegistry;
import com.lumensteward.clawbot.infrastructure.client.wechat.model.InternalMessage;
import com.lumensteward.clawbot.infrastructure.persistence.support.SessionResolver;
import com.lumensteward.clawbot.support.ToolRegistries;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 事件消息处理器测试（迭代 4 W20 首发体验）。
 *
 * <p>D2 的 P6 判据在本层的机制覆盖：关注欢迎语说得出自己能干什么（且能力段 = 实际下发集）、
 * 菜单 CLICK 有真实响应（帮助键 → 能力自述；其余键 → 路由对话引擎）、view 有确认、
 * 取关静默维持。
 */
class EventMessageHandlerTest {

    private final AgentOrchestrator orchestrator = mock(AgentOrchestrator.class);
    private final SessionResolver sessionResolver = mock(SessionResolver.class);
    private final DynamicConfigService dynamicConfig = mock(DynamicConfigService.class);
    private final ToolRegistry registry = ToolRegistries.productionTools();

    @Test
    @DisplayName("关注：欢迎语 = 可配正文 + 实际下发集的能力自述（暗启动工具不出现）")
    void welcomeContainsTruthfulCapabilitySection() {
        when(dynamicConfig.getString(eq("wechat.welcome-message"), anyString()))
                .thenReturn("欢迎回来！");
        when(dynamicConfig.getList(eq("orchestration.disabled-tools"), any()))
                .thenReturn(List.of("query_weather"));
        EventMessageHandler handler = new EventMessageHandler(
                orchestrator, sessionResolver, registry, dynamicConfig);

        String welcome = handler.handle(event("openid-w20", "subscribe", null));

        assertThat(welcome).startsWith("欢迎回来！").contains("我能帮你：");
        assertThat(welcome).contains("宠物档案").contains("快递");
        // 暗启动（disabled-tools）与条件可见（无缓存）的工具都不得被承诺
        assertThat(welcome).doesNotContain("城市天气").doesNotContain("无需重发图片");
    }

    @Test
    @DisplayName("CLICK「帮助」：确定性能力自述，不进对话引擎")
    void menuClickHelpReturnsCapabilitySelfDescription() {
        when(dynamicConfig.getList(eq("orchestration.disabled-tools"), any()))
                .thenReturn(List.of());
        EventMessageHandler handler = new EventMessageHandler(
                orchestrator, sessionResolver, registry, dynamicConfig);

        String reply = handler.handle(event("openid-w20", "CLICK", "帮助"));

        assertThat(reply).contains("当前实际能帮你做的事").contains("快递");
        verify(orchestrator, never()).run(any());
    }

    @Test
    @DisplayName("CLICK 其他 EventKey：作为用户消息路由进对话引擎，返回终态回复")
    void menuClickRoutesEventKeyIntoEngine() {
        when(dynamicConfig.getList(eq("orchestration.disabled-tools"), any()))
                .thenReturn(List.of());
        when(sessionResolver.resolveOrCreate(any())).thenReturn(9L);
        when(orchestrator.run(any())).thenReturn(new OrchestrationResult(
                "已为你登记", SessionState.IDLE, List.of(), null, 1, 1));
        EventMessageHandler handler = new EventMessageHandler(
                orchestrator, sessionResolver, registry, dynamicConfig);

        String reply = handler.handle(event("openid-w20", "CLICK", "记一下咪咪的疫苗"));

        ArgumentCaptor<OrchestrationRequest> captor = ArgumentCaptor.forClass(OrchestrationRequest.class);
        verify(orchestrator).run(captor.capture());
        assertThat(captor.getValue().userMessage()).isEqualTo("记一下咪咪的疫苗");
        assertThat(captor.getValue().sessionId()).isEqualTo(9L);
        assertThat(reply).isEqualTo("已为你登记");
    }

    @Test
    @DisplayName("view：确认回复携带链接；空 EventKey 静默")
    void viewEventGetsConfirmation() {
        EventMessageHandler handler = new EventMessageHandler(
                orchestrator, sessionResolver, registry, dynamicConfig);

        assertThat(handler.handle(event("openid-w20", "view", "https://example.com/menu")))
                .isEqualTo("菜单页面已打开：https://example.com/menu");
        assertThat(handler.handle(event("openid-w20", "view", null))).isNull();
    }

    @Test
    @DisplayName("取关与其他事件：静默（维持既有语义）")
    void otherEventsStaySilent() {
        EventMessageHandler handler = new EventMessageHandler(
                orchestrator, sessionResolver, registry, dynamicConfig);

        assertThat(handler.handle(event("openid-w20", "unsubscribe", null))).isNull();
        assertThat(handler.handle(event("openid-w20", "SCAN", null))).isNull();
    }

    @Test
    @DisplayName("standalone 构造：欢迎语回落默认正文（无能力段）、CLICK 回兜底文本，不抛异常")
    void standaloneConstructionFallsBackSafely() {
        EventMessageHandler handler = new EventMessageHandler();

        String welcome = handler.handle(event("openid-w20", "subscribe", null));
        assertThat(welcome).startsWith("你好呀，我是衔光管家～");

        String click = handler.handle(event("openid-w20", "CLICK", "查快递"));
        assertThat(click).contains("菜单已收到");
    }

    private static InternalMessage event(String openid, String event, String eventKey) {
        Map<String, String> attrs = event == null ? Map.of() : Map.of("Event", event);
        if (eventKey != null) {
            attrs = Map.of("Event", event, "EventKey", eventKey);
        }
        return new InternalMessage(openid, "event", "msg-w20", null, null, null, null, null, 0L, attrs);
    }
}
