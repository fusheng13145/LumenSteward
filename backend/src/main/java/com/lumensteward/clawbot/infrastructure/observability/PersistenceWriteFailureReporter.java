package com.lumensteward.clawbot.infrastructure.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 持久化写入失败上报器（D7 修复：消除「静默吞异常」）。
 *
 * <p><b>背景：</b>审计 / 日志类表（{@code log_audit}、{@code log_tool_call}、{@code wx_message}）
 * 采用「DB 不可用 → 只读降级」策略：写失败不抛出、不阻断主链路（SRS 9.5）。但此前的降级实现
 * 仅 {@code log.warn("...: err={}", e.getMessage())} 一行 INFO 级、且丢失了「表名 / 列名」上下文，
 * 导致 {@code Data too long for column 'trace_id'} 这类「结构化缺陷」在运行期完全不可见——
 * 表恒为空而无任何可观测信号（D7 阻断缺陷的直接成因）。
 *
 * <p><b>本类职责：</b>把任一「写入失败」变成两条<b>永不静默</b>的信号——
 * <ol>
 *   <li><b>可见日志</b>：以 {@code ERROR} 级输出，携带 {@code table}（表名）、{@code column}
 *       （从异常信息中提取的列名，提取不到时为 {@code unknown}）与<b>完整异常</b>
 *       （含堆栈，不再只保留 {@code getMessage()}）；</li>
 *   <li><b>可量化指标</b>：向 Micrometer 递增计数器 {@code persistence.write.failures}
 *       （标签 {@code table} / {@code column}），随 {@code /actuator/prometheus} 暴露，
 *       使「哪张表、哪一列、失败多少次」可被监控与告警。</li>
 * </ol>
 *
 * <p>主链路的降级语义不变：调用方 {@code catch} 后仍继续，<b>绝不</b>因上报而抛出新异常
 * （上报自身亦为 best-effort，避免可观测性设施反向影响业务可用性）。
 */
@Component
public class PersistenceWriteFailureReporter {

    private static final Logger log = LoggerFactory.getLogger(PersistenceWriteFailureReporter.class);

    /** 指标名（随 /actuator/prometheus 暴露）。 */
    public static final String METRIC_NAME = "persistence.write.failures";

    /** 无法从异常信息中解析出列名时的占位。 */
    public static final String UNKNOWN_COLUMN = "unknown";

    /**
     * 匹配 MySQL 驱动的两类列名提示：
     * <ul>
     *   <li>{@code Data too long for column 'trace_id' at row 1}</li>
     *   <li>{@code Unknown column 'trace_id' in 'field list'}</li>
     * </ul>
     * 同时兼容反引号包裹（部分日志/驱动版本）形式。
     */
    private static final Pattern COLUMN_PATTERN =
            Pattern.compile("for column [`']([^`']+)[`']|column [`']([^`']+)[`']");

    private final MeterRegistry meterRegistry;

    /**
     * 构造器注入（G-14）。
     *
     * @param meterRegistry 指标注册表（Spring Boot Actuator 自动装配；允许为 null 以兼容非 Spring 场景）
     */
    public PersistenceWriteFailureReporter(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * 上报一次持久化写入失败（日志 + 指标，均可观测为「失败」）。
     *
     * @param table 目标表名（如 {@code log_tool_call}）
     * @param cause 写入失败异常（不得为 null）
     */
    public void report(String table, Throwable cause) {
        String safeTable = (table == null || table.isBlank()) ? "unknown" : table;
        String column = columnOf(cause);
        String reason = rootReason(cause);

        // ① 可量化指标：persistence.write.failures{table,column}
        if (meterRegistry != null) {
            try {
                meterRegistry.counter(METRIC_NAME, "table", safeTable, "column", column).increment();
            } catch (RuntimeException metricsError) {
                // 上报设施自身故障不得影响业务降级路径
                log.error("持久化失败指标上报异常：table={} column={}", safeTable, column, metricsError);
            }
        }

        // ② 可见日志：ERROR 级 + 表名/列名/完整异常（含堆栈），确保「Data too long」等结构化缺陷不再隐形
        log.error("持久化写入失败（已降级，主链路继续）：table={} column={} cause={}", safeTable, column, reason, cause);
    }

    /**
     * 从异常链信息中提取列名（用于指标标签与日志）。
     *
     * @param cause 异常
     * @return 列名；无法解析时返回 {@link #UNKNOWN_COLUMN}
     */
    public static String columnOf(Throwable cause) {
        String message = deepestMessage(cause);
        if (message == null) {
            return UNKNOWN_COLUMN;
        }
        Matcher matcher = COLUMN_PATTERN.matcher(message);
        if (matcher.find()) {
            String matched = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            if (matched != null && !matched.isBlank()) {
                return matched;
            }
        }
        return UNKNOWN_COLUMN;
    }

    /**
     * 取异常链的「根因描述」（最内层异常信息，缺失时回退为最外层类名）。
     *
     * @param cause 异常
     * @return 根因描述
     */
    private static String rootReason(Throwable cause) {
        String deepest = deepestMessage(cause);
        if (deepest != null && !deepest.isBlank()) {
            return deepest;
        }
        return cause == null ? "unknown" : cause.getClass().getName();
    }

    /**
     * 沿 {@code cause} 链深入到最内层，优先返回带信息的消息。
     *
     * @param cause 异常
     * @return 最内层非空消息；全为空返回 null
     */
    private static String deepestMessage(Throwable cause) {
        String candidate = null;
        Throwable current = cause;
        while (current != null) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                candidate = current.getMessage();
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return candidate;
    }
}
