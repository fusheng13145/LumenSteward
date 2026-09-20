package com.lumensteward.clawbot.interfaces.admin;

import com.lumensteward.clawbot.application.admin.MonitorService;
import com.lumensteward.clawbot.application.orchestrator.model.OrchestrationSpan;
import com.lumensteward.clawbot.common.api.ApiResponse;
import com.lumensteward.clawbot.common.error.ErrorCode;
import com.lumensteward.clawbot.common.util.JsonUtils;
import com.lumensteward.clawbot.infrastructure.persistence.entity.OrchestrationTraceEntity;
import com.lumensteward.clawbot.infrastructure.persistence.service.OrchestrationTraceService;
import com.lumensteward.clawbot.interfaces.dto.monitor.TraceWaterfallVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 监控控制器「链路时序瀑布」端点单测（A-5 / T6）。
 *
 * <p>验证：命中时把实体反序列化为 {@link TraceWaterfallVO}（span 字段与状态透传）；
 * 未命中时返回 {@link ErrorCode#RESOURCE_NOT_FOUND}。
 */
class MonitorTraceControllerTest {

    private final MonitorService monitorService = mock(MonitorService.class);
    private final OrchestrationTraceService traceService = mock(OrchestrationTraceService.class);
    private final MonitorController controller = new MonitorController(monitorService, traceService);

    @Test
    @DisplayName("命中：返回时序瀑布 VO（含 span 时间轴）")
    void shouldReturnWaterfallWhenFound() {
        OrchestrationTraceEntity entity = new OrchestrationTraceEntity();
        entity.setTraceId("t-1");
        entity.setTotalMs(1500);
        entity.setTotalBudgetMs(25000);
        entity.setRounds(2);
        entity.setExceededBudget(false);
        entity.setSpanJson(JsonUtils.toJson(List.of(
                new OrchestrationSpan(OrchestrationSpan.SpanKind.LLM_ROUND, 1, 0, "LLM#0",
                        0L, 120L, OrchestrationSpan.SpanStatus.OK),
                new OrchestrationSpan(OrchestrationSpan.SpanKind.TOOL, 2, 0, "query_express",
                        130L, 60L, OrchestrationSpan.SpanStatus.DEGRADED))));
        when(traceService.findByTraceId("t-1")).thenReturn(Optional.of(entity));

        ApiResponse<TraceWaterfallVO> response = controller.trace("t-1");

        assertThat(response.getCode()).isEqualTo(ErrorCode.SUCCESS.getCode());
        TraceWaterfallVO vo = response.getData();
        assertThat(vo).isNotNull();
        assertThat(vo.traceId()).isEqualTo("t-1");
        assertThat(vo.totalMs()).isEqualTo(1500L);
        assertThat(vo.totalBudgetMs()).isEqualTo(25000);
        assertThat(vo.rounds()).isEqualTo(2);
        assertThat(vo.exceededBudget()).isFalse();
        assertThat(vo.spans()).hasSize(2);
        assertThat(vo.spans().get(0).kind()).isEqualTo("LLM_ROUND");
        assertThat(vo.spans().get(0).name()).isEqualTo("LLM#0");
        assertThat(vo.spans().get(0).status()).isEqualTo("OK");
        assertThat(vo.spans().get(1).kind()).isEqualTo("TOOL");
        assertThat(vo.spans().get(1).startOffsetMs()).isEqualTo(130L);
        assertThat(vo.spans().get(1).durationMs()).isEqualTo(60L);
        assertThat(vo.spans().get(1).status()).isEqualTo("DEGRADED");
    }

    @Test
    @DisplayName("未命中：返回 RESOURCE_NOT_FOUND")
    void shouldReturnNotFoundWhenAbsent() {
        when(traceService.findByTraceId("missing")).thenReturn(Optional.empty());

        ApiResponse<TraceWaterfallVO> response = controller.trace("missing");

        assertThat(response.getCode()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND.getCode());
        assertThat(response.getData()).isNull();
    }
}
