package com.lumensteward.clawbot.infrastructure.persistence.service;

import com.lumensteward.clawbot.application.orchestrator.OrchestrationTracedEvent;
import com.lumensteward.clawbot.application.orchestrator.model.OrchestrationSpan;
import com.lumensteward.clawbot.infrastructure.persistence.entity.OrchestrationTraceEntity;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.OrchestrationTraceMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 链路时序追踪服务单测（A-5 / T6）。
 *
 * <p>验证：字段落库正确（含脱敏 openid 与 spanJson）、落库异常不外抛、按 traceId 查询透传。
 */
class OrchestrationTraceServiceImplTest {

    private final OrchestrationTraceMapper mapper = mock(OrchestrationTraceMapper.class);
    private final OrchestrationTraceServiceImpl service = new OrchestrationTraceServiceImpl(mapper);

    private static OrchestrationTracedEvent sampleEvent() {
        return new OrchestrationTracedEvent("t-1", "openid-abcdefgh", 5L, 1234L, 25000, 2, true,
                List.of(new OrchestrationSpan(OrchestrationSpan.SpanKind.TOOL, 1, 0, "query_express",
                        10L, 40L, OrchestrationSpan.SpanStatus.OK)));
    }

    @Test
    @DisplayName("persist 落库：字段与 spanJson 正确，openid 已脱敏")
    void persistInsertsMaskedFieldsAndSpanJson() {
        OrchestrationTraceEntity[] captured = new OrchestrationTraceEntity[1];
        doAnswer(invocation -> {
            captured[0] = invocation.getArgument(0);
            return null;
        }).when(mapper).insert(any(OrchestrationTraceEntity.class));

        service.persist(sampleEvent());

        assertThat(captured[0]).isNotNull();
        assertThat(captured[0].getTraceId()).isEqualTo("t-1");
        // 事件已在构造内脱敏：openid-abcdefgh → open****efgh
        assertThat(captured[0].getOpenid()).isEqualTo("open****efgh");
        assertThat(captured[0].getOpenid()).doesNotContain("abcdefgh");
        assertThat(captured[0].getSessionId()).isEqualTo(5L);
        assertThat(captured[0].getTotalMs()).isEqualTo(1234);
        assertThat(captured[0].getTotalBudgetMs()).isEqualTo(25000);
        assertThat(captured[0].getRounds()).isEqualTo(2);
        assertThat(captured[0].getExceededBudget()).isTrue();
        assertThat(captured[0].getSpanJson())
                .contains("query_express").contains("TOOL").contains("OK");
    }

    @Test
    @DisplayName("落库抛异常不向上传播（BR-29 保护优先，绝不影响主链路）")
    void insertFailureSwallowed() {
        doThrow(new RuntimeException("db down")).when(mapper).insert(any(OrchestrationTraceEntity.class));
        // 不抛即为通过
        service.persist(sampleEvent());
    }

    @Test
    @DisplayName("persist(null) 直接返回，不触碰 Mapper")
    void persistNullIsNoOp() {
        service.persist(null);
        verify(mapper, never()).insert(any(OrchestrationTraceEntity.class));
    }

    @Test
    @DisplayName("findByTraceId 透传查询并返回实体；空入参不查库")
    void findByTraceIdDelegates() {
        OrchestrationTraceEntity entity = new OrchestrationTraceEntity();
        entity.setTraceId("t-1");
        when(mapper.selectOne(any())).thenReturn(entity);

        Optional<OrchestrationTraceEntity> found = service.findByTraceId("t-1");

        assertThat(found).isPresent();
        assertThat(found.get().getTraceId()).isEqualTo("t-1");
        verify(mapper).selectOne(any());

        assertThat(service.findByTraceId("  ")).isEmpty();
    }
}
