package com.lumensteward.clawbot.application.console;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 控制台事件中继（FR-08 / 迭代 3 Wave 2 T3）。
 *
 * <p>监听 {@link ConsoleEvent}，转发到 {@link ConsoleEventBuffer} 广播。
 * 与编排器解耦：编排器仅 {@code publishEvent}，本组件负责落地到 SSE 缓冲，
 * 使观测通道的写入不影响主链路（同步派发，单连接慢不会跨连接放大）。
 */
@Component
public class ConsoleEventRelay {

    private static final Logger log = LoggerFactory.getLogger(ConsoleEventRelay.class);

    private final ConsoleEventBuffer consoleEventBuffer;

    public ConsoleEventRelay(ConsoleEventBuffer consoleEventBuffer) {
        this.consoleEventBuffer = consoleEventBuffer;
    }

    /**
     * 接收控制台事件并广播。
     *
     * @param event 控制台事件
     */
    @EventListener
    public void onConsoleEvent(ConsoleEvent event) {
        consoleEventBuffer.push(event);
    }
}
