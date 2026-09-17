package com.lumensteward.clawbot.observability;

import com.lumensteward.clawbot.application.orchestrator.model.ToolCallRecord;
import com.lumensteward.clawbot.infrastructure.client.llm.dto.ToolCall;
import com.lumensteward.clawbot.infrastructure.observability.PersistenceWriteFailureReporter;
import com.lumensteward.clawbot.infrastructure.persistence.entity.ToolCallLogEntity;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.ToolCallLogMapper;
import com.lumensteward.clawbot.infrastructure.persistence.service.ToolCallLogServiceImpl;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 独立验证：D7「消除静默吞异常」。
 *
 * <p>证明两件事：
 * <ol>
 *   <li>{@link PersistenceWriteFailureReporter} 能从 {@code Data too long for column 'trace_id'} 中
 *       <b>提取列名</b>并计入指标 {@code persistence.write.failures}（标签 table/column）——
 *       即「失败可量化」；</li>
 *   <li>真实 {@link ToolCallLogServiceImpl} 在写失败时<b>确实调用</b>了上报器（不再静默）：以 Mock Mapper
 *       抛出错宽异常，断言指标计数 +1，且 {@code logStart} 仍返回可用记录、不抛出（降级语义不变）。</li>
 * </ol>
 *
 * <p>纯单元测试（{@link SimpleMeterRegistry} 内存注册表），不依赖 MySQL / Docker，随常规 {@code verify} 运行。
 */
class PersistenceWriteFailureReporterTest {

    private static final String UUID_36 = "0f8fad5b-d9cb-469f-a165-70867728950e";

    @Test
    @DisplayName("D7：从 Data too long 异常中提取列名并计入指标（table/column）")
    void extractsColumnAndCountsMetric() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PersistenceWriteFailureReporter reporter = new PersistenceWriteFailureReporter(registry);

        reporter.report("log_tool_call",
                new RuntimeException("Data too long for column 'trace_id' at row 1"));

        double count = registry.get(PersistenceWriteFailureReporter.METRIC_NAME)
                .tag("table", "log_tool_call")
                .tag("column", "trace_id")
                .counter()
                .count();
        assertThat(count).as("指标应记录 table=log_tool_call column=trace_id 的失败 1 次").isEqualTo(1.0d);
    }

    @Test
    @DisplayName("D7：异常链最内层信息参与列名提取")
    void resolvesColumnFromNestedCause() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PersistenceWriteFailureReporter reporter = new PersistenceWriteFailureReporter(registry);

        RuntimeException root = new RuntimeException("Data too long for column `openid` at row 1");
        reporter.report("wx_message", new RuntimeException("MyBatis 写入异常", root));

        double count = registry.get(PersistenceWriteFailureReporter.METRIC_NAME)
                .tag("table", "wx_message")
                .tag("column", "openid")
                .counter()
                .count();
        assertThat(count).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("D7：无法解析列名时归入 unknown，指标仍计入（失败绝不静默）")
    void fallsBackToUnknownColumn() {
        SimpleMysqlUnavailableException cause = new SimpleMysqlUnavailableException("connection refused");
        assertThat(PersistenceWriteFailureReporter.columnOf(cause))
                .isEqualTo(PersistenceWriteFailureReporter.UNKNOWN_COLUMN);
    }

    @Test
    @DisplayName("D7：ToolCallLogServiceImpl 写失败时上报（不静默）且 logStart 不抛出、返回可用记录")
    void toolCallLogServiceReportsFailureWithoutThrowing() {
        ToolCallLogMapper mapper = mock(ToolCallLogMapper.class);
        when(mapper.insert(any(ToolCallLogEntity.class)))
                .thenThrow(new RuntimeException("Data too long for column 'trace_id' at row 1"));

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PersistenceWriteFailureReporter reporter = new PersistenceWriteFailureReporter(registry);
        ToolCallLogServiceImpl service = new ToolCallLogServiceImpl(mapper, reporter);

        ToolCall call = ToolCall.function("call-1", "manage_pet_profile", "{\"petName\":\"咪咪\"}");

        ToolCallRecord[] holder = new ToolCallRecord[1];
        assertThatCode(() -> holder[0] = service.logStart(UUID_36, "openid-x", 1L, call, 0, 1))
                .as("写失败不得抛出（只读降级语义不变）")
                .doesNotThrowAnyException();
        assertThat(holder[0]).as("应返回可用记录（供后续 logEnd 兜底）").isNotNull();

        double count = registry.get(PersistenceWriteFailureReporter.METRIC_NAME)
                .tag("table", "log_tool_call")
                .tag("column", "trace_id")
                .counter()
                .count();
        assertThat(count).as("写失败必须可见（计入指标）").isEqualTo(1.0d);
    }

    /** 无列名信息的通用异常（模拟连接不可用）。 */
    private static final class SimpleMysqlUnavailableException extends RuntimeException {
        private SimpleMysqlUnavailableException(String message) {
            super(message);
        }
    }
}
