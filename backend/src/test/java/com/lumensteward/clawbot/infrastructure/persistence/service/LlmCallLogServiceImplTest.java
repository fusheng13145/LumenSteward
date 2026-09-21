package com.lumensteward.clawbot.infrastructure.persistence.service;

import com.lumensteward.clawbot.application.ratelimit.CostBudgetService.LlmCallUsage;
import com.lumensteward.clawbot.common.enums.LlmCallPurpose;
import com.lumensteward.clawbot.common.util.MaskUtils;
import com.lumensteward.clawbot.infrastructure.observability.TraceContext;
import com.lumensteward.clawbot.infrastructure.persistence.entity.LlmCallEntity;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.LlmCallMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * LLM 调用计量明细落库服务测试（迭代 4 W5 / B-4）。
 *
 * <p>覆盖三条纪律：<b>脱敏集中</b>（原始 openid 绝不出本方法，BR-21）、<b>total 由 prompt+completion
 * 推导</b>（与 Redis 日预算同一算法，否则 FR-17 AC① 的明细/计数互查不成立）、<b>best-effort</b>
 * （插入异常只记 WARN，不得影响对话主链路）。
 */
class LlmCallLogServiceImplTest {

    private static final String RAW_OPENID = "oABCDEFGHIJKLMN";

    private final LlmCallMapper mapper = mock(LlmCallMapper.class);
    private final LlmCallLogServiceImpl service = new LlmCallLogServiceImpl(mapper);
    private final ArgumentCaptor<LlmCallEntity> captor = ArgumentCaptor.forClass(LlmCallEntity.class);

    private static LlmCallUsage chat(int prompt, int completion) {
        return new LlmCallUsage(LlmCallPurpose.CHAT, "mock", "deepseek-chat",
                RAW_OPENID, 42L, "trace-1", prompt, completion);
    }

    @Test
    @DisplayName("落库字段：用途/供应商/模型/会话照录，openid 为脱敏形态，total=prompt+completion")
    void shouldRecordMaskedRow() {
        service.record(chat(620, 45));

        verify(mapper).insert(captor.capture());
        LlmCallEntity entity = captor.getValue();
        assertThat(entity.getPurpose()).isEqualTo("CHAT");
        assertThat(entity.getProvider()).isEqualTo("mock");
        assertThat(entity.getModel()).isEqualTo("deepseek-chat");
        assertThat(entity.getSessionId()).isEqualTo(42L);
        assertThat(entity.getTraceId()).isEqualTo("trace-1");
        assertThat(entity.getPromptTokens()).isEqualTo(620);
        assertThat(entity.getCompletionTokens()).isEqualTo(45);
        assertThat(entity.getTotalTokens()).isEqualTo(665);
        assertThat(entity.getOpenid()).isEqualTo(MaskUtils.openid(RAW_OPENID));
        assertThat(entity.getOpenid()).doesNotContain(RAW_OPENID);
    }

    @Test
    @DisplayName("无用户归属的调用（意图分类）落 null，不伪造归属")
    void shouldRecordNullOpenidForUnattributedCall() {
        service.record(new LlmCallUsage(LlmCallPurpose.INTENT, "mock", null, null, null, null, 180, 12));

        verify(mapper).insert(captor.capture());
        LlmCallEntity entity = captor.getValue();
        assertThat(entity.getPurpose()).isEqualTo("INTENT");
        assertThat(entity.getOpenid()).isNull();
        assertThat(entity.getModel()).isNull();
        assertThat(entity.getTotalTokens()).isEqualTo(192);
    }

    @Test
    @DisplayName("调用点未带 traceId 时回落当前线程 MDC（意图分类等链路内调用仍可下钻）")
    void shouldFallbackToMdcTraceId() {
        TraceContext.setTraceId("trace-from-mdc");
        try {
            service.record(new LlmCallUsage(LlmCallPurpose.MEMORY_EXTRACT, "mock", null,
                    RAW_OPENID, 7L, null, 10, 5));
        } finally {
            TraceContext.clear();
        }

        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getTraceId()).isEqualTo("trace-from-mdc");
        assertThat(captor.getValue().getPurpose()).isEqualTo("MEMORY_EXTRACT");
    }

    @Test
    @DisplayName("provider 回报负数 token：夹紧为 0，不写入不可解释的负值")
    void shouldClampNegativeTokens() {
        service.record(new LlmCallUsage(LlmCallPurpose.CHAT, "mock", "m", null, null, null, -5, -3));

        verify(mapper).insert(captor.capture());
        LlmCallEntity entity = captor.getValue();
        assertThat(entity.getPromptTokens()).isZero();
        assertThat(entity.getCompletionTokens()).isZero();
        assertThat(entity.getTotalTokens()).isZero();
    }

    @Test
    @DisplayName("插入异常被吞掉：计量写入不得影响主链路")
    void shouldSwallowInsertFailure() {
        doThrow(new IllegalStateException("db down")).when(mapper).insert(any(LlmCallEntity.class));

        assertThatCode(() -> service.record(chat(100, 20))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("null 或缺用途的计量入参直接忽略，不产生脏行")
    void shouldIgnoreIncompleteUsage() {
        service.record(null);
        service.record(new LlmCallUsage(null, "mock", "m", null, null, null, 10, 1));

        verify(mapper, never()).insert(any(LlmCallEntity.class));
    }
}
