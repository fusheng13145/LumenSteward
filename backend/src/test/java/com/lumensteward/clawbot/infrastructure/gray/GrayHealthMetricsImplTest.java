package com.lumensteward.clawbot.infrastructure.gray;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.lumensteward.clawbot.application.gray.GrayHealthSnapshot;
import com.lumensteward.clawbot.infrastructure.persistence.entity.AnomalyEventEntity;
import com.lumensteward.clawbot.infrastructure.persistence.entity.OrchestrationTraceEntity;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.AnomalyEventMapper;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.OrchestrationTraceMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 熔断健康度取数实现测试（W2）。
 *
 * <p>覆盖三处判定口径：分子<b>不含 L1</b>（接入层噪声不得触发全局回滚）、
 * P95 取第 ceil(0.95×n) 个样本、以及 MyBatis 投影行可能为 null（D9 同源）时不得 NPE。
 */
class GrayHealthMetricsImplTest {

    private final OrchestrationTraceMapper traceMapper = mock(OrchestrationTraceMapper.class);
    private final AnomalyEventMapper anomalyMapper = mock(AnomalyEventMapper.class);

    private final GrayHealthMetricsImpl metrics =
            new GrayHealthMetricsImpl(traceMapper, anomalyMapper);

    /** 未启动 MyBatis 上下文，手工注册 TableInfo 供 Lambda 条件解析。 */
    @BeforeAll
    static void initTableInfoCache() {
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, OrchestrationTraceEntity.class);
        TableInfoHelper.initTableInfo(assistant, AnomalyEventEntity.class);
    }

    private static OrchestrationTraceEntity trace(Integer totalMs, boolean exceeded) {
        OrchestrationTraceEntity entity = new OrchestrationTraceEntity();
        entity.setTotalMs(totalMs);
        entity.setExceededBudget(exceeded);
        return entity;
    }

    @Test
    @DisplayName("轮次=窗口内链路行数、错误率=L2~L4 异常/轮次；WHERE 明确排除 L1")
    void shouldCountTurnsAndExcludeL1() {
        when(traceMapper.selectList(any())).thenReturn(
                List.of(trace(100, false), trace(200, true), trace(300, false), trace(400, false)));
        when(anomalyMapper.selectCount(any())).thenReturn(2L);

        GrayHealthSnapshot snapshot = metrics.measure(5);

        assertThat(snapshot.turns()).isEqualTo(4);
        assertThat(snapshot.anomalies()).isEqualTo(2);
        assertThat(snapshot.errorRatePercent()).isEqualByComparingTo("50.00");
        assertThat(snapshot.exceededBudget()).isEqualTo(1);
        assertThat(snapshot.windowMinutes()).isEqualTo(5);

        ArgumentCaptor<LambdaQueryWrapper<AnomalyEventEntity>> captor = wrapperCaptor();
        verify(anomalyMapper).selectCount(captor.capture());
        String sql = captor.getValue().getTargetSql();
        assertThat(sql).contains("layer IN");
        assertThat(captor.getValue().getParamNameValuePairs().values())
                .contains("L2", "L3", "L4")
                .doesNotContain("L1");
    }

    @Test
    @DisplayName("P95 取升序第 ceil(0.95×n) 个样本：20 条中第 19 个（非最大值、非平均值）")
    void shouldComputeP95() {
        List<OrchestrationTraceEntity> traces = new ArrayList<>();
        for (int ms = 10; ms <= 200; ms += 10) {
            traces.add(trace(ms, false));
        }
        when(traceMapper.selectList(any())).thenReturn(traces);
        when(anomalyMapper.selectCount(any())).thenReturn(0L);

        assertThat(metrics.measure(5).p95LatencyMs()).isEqualTo(190);
    }

    @Test
    @DisplayName("投影列全为 NULL 的行（MyBatis 给出 null 元素，D9 同源）：跳过不 NPE，且不计入 P95")
    void shouldTolerateNullRows() {
        when(traceMapper.selectList(any())).thenReturn(Arrays.asList(trace(500, false), null));
        when(anomalyMapper.selectCount(any())).thenReturn(null);

        GrayHealthSnapshot snapshot = metrics.measure(5);

        assertThat(snapshot.turns()).isEqualTo(2);
        assertThat(snapshot.anomalies()).isZero();
        assertThat(snapshot.errorRatePercent()).isEqualByComparingTo("0.00");
        assertThat(snapshot.p95LatencyMs()).isEqualTo(500);
    }

    @Test
    @DisplayName("空窗：返回全零快照（分母为 0 时不算错误率，交由最小样本数闸门拦下）")
    void shouldReturnEmptySnapshotWithoutTraces() {
        when(traceMapper.selectList(any())).thenReturn(List.of());

        GrayHealthSnapshot snapshot = metrics.measure(5);

        assertThat(snapshot.turns()).isZero();
        assertThat(snapshot.errorRatePercent()).isEqualByComparingTo("0");
        assertThat(snapshot.p95LatencyMs()).isZero();
    }

    @Test
    @DisplayName("窗口配置缺失/非正值：退化为默认 5 分钟，不把 SQL 时间条件写成「现在」")
    void shouldFallBackToDefaultWindow() {
        when(traceMapper.selectList(any())).thenReturn(List.of());

        assertThat(metrics.measure(0).windowMinutes()).isEqualTo(5);
        assertThat(metrics.measure(-3).windowMinutes()).isEqualTo(5);
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<LambdaQueryWrapper<AnomalyEventEntity>> wrapperCaptor() {
        return ArgumentCaptor.forClass(LambdaQueryWrapper.class);
    }
}
