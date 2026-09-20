package com.lumensteward.clawbot.console;

import com.lumensteward.clawbot.application.console.ConsoleEvent;
import com.lumensteward.clawbot.application.console.ConsoleEventType;
import com.lumensteward.clawbot.application.console.ConsoleEventBuffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * ConsoleEventBuffer 单元测试（FR-08 / T3）：环形淘汰、replay 续传、广播、心跳注销。
 */
class ConsoleEventBufferTest {

    private final ConsoleEventBuffer buffer = new ConsoleEventBuffer();

    private ConsoleEvent event(String traceId) {
        return new ConsoleEvent(ConsoleEventType.TOOL_START, traceId, "openid-raw", 1L, 0, "t", 1, null);
    }

    @Test
    @DisplayName("环形淘汰：超过容量 100 后丢弃最旧，size 恒定 100")
    void shouldEvictOldestWhenOverCapacity() {
        for (int i = 0; i < ConsoleEventBuffer.CAPACITY + 5; i++) {
            buffer.push(event("t" + i));
        }
        assertThat(buffer.bufferedSize()).isEqualTo(ConsoleEventBuffer.CAPACITY);
        assertThat(buffer.activeConnections()).isZero();
    }

    @Test
    @DisplayName("replay(lastEventId)：返回序号严格大于 lastEventId 的事件且有序")
    void shouldReplayEventsAfterLastIdInOrder() {
        for (int i = 0; i < 10; i++) {
            buffer.push(event("t" + i));
        }
        // 序号从 1 开始；序号 6 之后共 4 条（7..10）
        var replayed = buffer.replay(6);
        assertThat(replayed).hasSize(4);
        long prev = 0;
        for (var e : replayed) {
            assertThat(e.getSeq()).isGreaterThan(prev);
            prev = e.getSeq();
        }
        assertThat(replayed.get(0).getSeq()).isEqualTo(7);
    }

    @Test
    @DisplayName("push 广播：注册连接收到事件；无活跃连接不抛异常")
    void shouldBroadcastToRegisteredEmitter() throws IOException {
        SseEmitter emitter = mock(SseEmitter.class);
        buffer.register(emitter);
        assertThat(buffer.activeConnections()).isEqualTo(1);

        buffer.push(event("broadcast"));

        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        buffer.unregister(emitter);
        assertThat(buffer.activeConnections()).isZero();
    }

    @Test
    @DisplayName("推送失败的连接被自动注销")
    void shouldUnregisterOnSendFailure() throws IOException {
        SseEmitter bad = mock(SseEmitter.class);
        doThrow(new IOException("closed")).when(bad).send(any(SseEmitter.SseEventBuilder.class));
        buffer.register(bad);
        buffer.push(event("x"));
        assertThat(buffer.activeConnections()).isZero();
    }
}
