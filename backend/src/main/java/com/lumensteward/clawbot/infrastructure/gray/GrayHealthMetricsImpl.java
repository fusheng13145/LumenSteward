package com.lumensteward.clawbot.infrastructure.gray;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lumensteward.clawbot.application.gray.GrayHealthMetrics;
import com.lumensteward.clawbot.application.gray.GrayHealthSnapshot;
import com.lumensteward.clawbot.common.enums.AnomalyLayer;
import com.lumensteward.clawbot.infrastructure.persistence.entity.AnomalyEventEntity;
import com.lumensteward.clawbot.infrastructure.persistence.entity.OrchestrationTraceEntity;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.AnomalyEventMapper;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.OrchestrationTraceMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 灰度熔断健康度取数实现（FR-22 / W2），读既有表：
 * {@code log_orchestration_trace}（分母 + 时延）与 {@code log_anomaly_event}（分子）。
 *
 * <p>分子<b>只取 L2~L4</b>：L1 是接入层（验签失败、时间戳越界、重复投递），多由外部重投或探测
 * 造成，与灰度中功能是否健康无关；把 L1 计入会让一次回调风暴误触发全局回滚。
 *
 * <p>P95 在窗口样本上内存排序求得：窗口默认 5 分钟、单链路一行，样本量与后台看板同量级，
 * 不为此引入 SQL 百分位函数（MySQL 8 的 {@code PERCENT_RANK} 窗口函数会绕开 MyBatis-Plus 的
 * 通用查询构造，收益不抵复杂度）。
 */
@Service
public class GrayHealthMetricsImpl implements GrayHealthMetrics {

    private static final Logger log = LoggerFactory.getLogger(GrayHealthMetricsImpl.class);

    /** 配置缺失时的默认窗口（分钟）。 */
    private static final int DEFAULT_WINDOW_MINUTES = 5;

    private final OrchestrationTraceMapper traceMapper;
    private final AnomalyEventMapper anomalyMapper;

    /**
     * 构造器注入（G-14）。
     *
     * @param traceMapper   链路时序 Mapper
     * @param anomalyMapper 四层异常 Mapper
     */
    public GrayHealthMetricsImpl(OrchestrationTraceMapper traceMapper,
                                 AnomalyEventMapper anomalyMapper) {
        this.traceMapper = traceMapper;
        this.anomalyMapper = anomalyMapper;
    }

    @Override
    public GrayHealthSnapshot measure(int windowMinutes) {
        int window = windowMinutes > 0 ? windowMinutes : DEFAULT_WINDOW_MINUTES;
        LocalDateTime from = LocalDateTime.now().minusMinutes(window);
        LocalDateTime now = LocalDateTime.now();

        List<OrchestrationTraceEntity> traces = traceMapper.selectList(
                new LambdaQueryWrapper<OrchestrationTraceEntity>()
                        .select(OrchestrationTraceEntity::getTotalMs,
                                OrchestrationTraceEntity::getExceededBudget)
                        .ge(OrchestrationTraceEntity::getCreatedAt, from));
        long turns = traces == null ? 0L : traces.size();
        if (turns == 0L) {
            return new GrayHealthSnapshot(window, 0L, 0L, BigDecimal.ZERO, 0, 0L, now);
        }

        List<Integer> latencies = new ArrayList<>(traces.size());
        long exceeded = 0L;
        for (OrchestrationTraceEntity trace : traces) {
            // MyBatis 对「投影列全为 NULL」的行会给出 null 元素（D9 同源），逐行判空
            if (trace == null) {
                continue;
            }
            if (trace.getTotalMs() != null) {
                latencies.add(trace.getTotalMs());
            }
            if (Boolean.TRUE.equals(trace.getExceededBudget())) {
                exceeded++;
            }
        }

        Long anomalies = anomalyMapper.selectCount(new LambdaQueryWrapper<AnomalyEventEntity>()
                .ge(AnomalyEventEntity::getCreatedAt, from)
                .in(AnomalyEventEntity::getLayer,
                        List.of(AnomalyLayer.L2.name(), AnomalyLayer.L3.name(), AnomalyLayer.L4.name())));
        long errorCount = anomalies == null ? 0L : anomalies;

        BigDecimal rate = BigDecimal.valueOf(errorCount)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(turns), 2, RoundingMode.HALF_UP);
        return new GrayHealthSnapshot(window, turns, errorCount, rate, p95(latencies), exceeded, now);
    }

    /** P95：升序取第 ceil(0.95*n) 个样本（无样本返回 0）。 */
    private static int p95(List<Integer> latencies) {
        if (latencies.isEmpty()) {
            return 0;
        }
        List<Integer> sorted = new ArrayList<>(latencies);
        Collections.sort(sorted);
        int index = (int) Math.ceil(sorted.size() * 0.95) - 1;
        return sorted.get(Math.min(Math.max(index, 0), sorted.size() - 1));
    }
}
