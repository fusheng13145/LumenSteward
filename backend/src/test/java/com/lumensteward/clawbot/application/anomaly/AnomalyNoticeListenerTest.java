package com.lumensteward.clawbot.application.anomaly;

import com.lumensteward.clawbot.common.enums.AnomalyLayer;
import com.lumensteward.clawbot.infrastructure.persistence.service.AnomalyEventService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 异常事件订阅落库测试（迭代 4 W1）。
 *
 * <p>证明「编排器发布 {@link AnomalyNotice} → 监听器写表」这条链路成立：正常委托、且写库异常
 * 不得回流到主链路。
 */
class AnomalyNoticeListenerTest {

    private final AnomalyEventService service = mock(AnomalyEventService.class);
    private final AnomalyNoticeListener listener = new AnomalyNoticeListener(service);

    @Test
    @DisplayName("事件按原样委托写服务")
    void shouldDelegateToService() {
        AnomalyNotice notice = new AnomalyNotice(AnomalyLayer.L2, "LLM_TIMEOUT", "orchestrator",
                "openid-1", "round=0");

        listener.onAnomaly(notice);

        verify(service).record(notice);
    }

    @Test
    @DisplayName("写库异常被吞掉，不影响主链路")
    void shouldSwallowServiceFailure() {
        doThrow(new IllegalStateException("db down")).when(service).record(any());

        assertThatCode(() -> listener.onAnomaly(AnomalyNotice.of(AnomalyLayer.L1, "X", "src")))
                .doesNotThrowAnyException();
    }
}
