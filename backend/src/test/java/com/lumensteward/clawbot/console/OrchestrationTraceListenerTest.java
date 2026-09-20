package com.lumensteward.clawbot.console;

import com.lumensteward.clawbot.application.console.OrchestrationTraceListener;
import com.lumensteward.clawbot.application.orchestrator.OrchestrationTracedEvent;
import com.lumensteward.clawbot.application.orchestrator.model.OrchestrationSpan;
import com.lumensteward.clawbot.infrastructure.persistence.service.OrchestrationTraceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 链路时序追踪监听器单测（A-5 / T6）。
 *
 * <p>验证转交服务落库，且服务抛异常时监听器<b>不外抛</b>（保护主链路）。
 */
class OrchestrationTraceListenerTest {

    private final OrchestrationTraceService traceService = mock(OrchestrationTraceService.class);
    private final OrchestrationTraceListener listener = new OrchestrationTraceListener(traceService);

    private static OrchestrationTracedEvent sampleEvent() {
        return new OrchestrationTracedEvent("trace-l", "openid-listen-xyz", 2L, 900L, 25000, 1, false,
                List.of(new OrchestrationSpan(OrchestrationSpan.SpanKind.LLM_ROUND, 1, 0, "LLM#0",
                        0L, 50L, OrchestrationSpan.SpanStatus.OK)));
    }

    @Test
    @DisplayName("接收事件并转交服务落库")
    void shouldDelegateToService() {
        OrchestrationTracedEvent event = sampleEvent();

        listener.onTraced(event);

        verify(traceService).persist(event);
    }

    @Test
    @DisplayName("服务抛异常时监听器内部吞掉，不影响主链路")
    void shouldSwallowServiceFailure() {
        doThrow(new RuntimeException("db down")).when(traceService).persist(any(OrchestrationTracedEvent.class));

        // 不抛即为通过
        listener.onTraced(sampleEvent());
    }
}
