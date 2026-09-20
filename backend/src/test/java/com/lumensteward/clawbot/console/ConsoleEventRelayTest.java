package com.lumensteward.clawbot.console;

import com.lumensteward.clawbot.application.console.ConsoleEvent;
import com.lumensteward.clawbot.application.console.ConsoleEventType;
import com.lumensteward.clawbot.application.console.ConsoleEventBuffer;
import com.lumensteward.clawbot.application.console.ConsoleEventRelay;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * ConsoleEventRelay 中继测试（FR-08 / T3）：收到 ApplicationEvent 后转发到缓冲广播。
 */
class ConsoleEventRelayTest {

    @Test
    @DisplayName("@EventListener 收到 ConsoleEvent → 缓冲广播给活跃连接")
    void shouldRelayEventToBuffer() throws IOException {
        ConsoleEventBuffer buffer = new ConsoleEventBuffer();
        SseEmitter emitter = mock(SseEmitter.class);
        buffer.register(emitter);

        ConsoleEventRelay relay = new ConsoleEventRelay(buffer);
        relay.onConsoleEvent(new ConsoleEvent(ConsoleEventType.TOOL_START, "trace-x", "openid-raw", 2L, 1, "tool", 1, null));

        verify(emitter, org.mockito.Mockito.times(1)).send(any(SseEmitter.SseEventBuilder.class));
        buffer.unregister(emitter);
    }
}
