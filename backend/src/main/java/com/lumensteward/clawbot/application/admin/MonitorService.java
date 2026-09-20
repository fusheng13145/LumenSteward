package com.lumensteward.clawbot.application.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.lumensteward.clawbot.common.enums.ToolStatus;
import com.lumensteward.clawbot.infrastructure.persistence.entity.AnomalyEventEntity;
import com.lumensteward.clawbot.infrastructure.persistence.entity.AuditLogEntity;
import com.lumensteward.clawbot.infrastructure.persistence.entity.ToolCallLogEntity;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.AnomalyEventMapper;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.AuditLogMapper;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.ToolCallLogMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 会话与工具调用监控聚合（FR-17 / T4）与降级与拦截看板（A-3 / T5）。
 *
 * <p>本服务只做<b>只读聚合</b>（BR-23 监控页只读），不承载任何业务写入。全部指标均由
 * {@code wx_message}/{@code wx_user}/{@code log_tool_call}/{@code log_audit}
 * /{@code log_anomaly_event} 的
 * COUNT/SUM/AVG 聚合<b>直接产出</b>，可用等价 SQL 复核（AC-E5），<b>非估算</b>。
 *
 * <p><b>复用策略：</b>
 * <ul>
 *   <li>概览的「今日消息量 / 活跃用户 / 调用量 / 成功率 / 降级数」复用 {@link DashboardService}；</li>
 *   <li>按工具名成功率、调用量/耗时分布复用 {@link ToolLogQueryService#stats}；
 *       本服务仅补齐既有服务未覆盖的缺口聚合（平均耗时、趋势、意图分布、延迟直方图、降级率、四层分布）。</li>
 * </ul>
 *
 * <p><b>数据来源边界：</b>执行性幻觉拦截由编排器在一致性校验拦截处写入 {@code log_audit}
 * （{@code reg_type=SAFETY}、{@code action=EXECUTION_HALLUCINATION}），是本看板幻觉拦截计数的唯一事实来源。
 */
@Service
public class MonitorService {

    /** 意图域展示顺序（对齐 FR-05：image_recognition / tts / express / navigation / pet_profile / chat）。 */
    private static final List<String> INTENT_ORDER = List.of(
            "image_recognition", "tts", "express", "navigation", "pet_profile", "chat");

    /** 四层异常层次顺序（对齐 SRS 2.3.5）。 */
    private static final List<String> LAYER_ORDER = List.of("L1", "L2", "L3", "L4");

    /** 层次中文名（对齐 SRS 2.3.5 表头）。 */
    private static final Map<String, String> LAYER_LABELS = Map.of(
            "L1", "L1 接入层",
            "L2", "L2 认知层",
            "L3", "L3 工具层",
            "L4", "L4 输出层");

    /** 审计资源类型：安全域（幻觉拦截留痕）。 */
    private static final String REG_TYPE_SAFETY = "SAFETY";

    /** 审计动作：执行性幻觉拦截。 */
    private static final String ACTION_HALLUCINATION = "EXECUTION_HALLUCINATION";

    /** 审计动作：对外泄漏（核心 KPI，正常恒为 0）。 */
    private static final String ACTION_EXTERNAL_LEAK = "EXTERNAL_LEAK";

    /** 日粒度时间桶表达式（MySQL）。 */
    private static final String BUCKET_DAY = "DATE_FORMAT(created_at, '%Y-%m-%d')";

    /** 时粒度时间桶表达式（MySQL）。 */
    private static final String BUCKET_HOUR = "DATE_FORMAT(created_at, '%Y-%m-%d %H:00')";

    private final DashboardService dashboardService;
    private final ToolLogQueryService toolLogQueryService;
    private final ToolCallLogMapper toolCallLogMapper;
    private final AuditLogMapper auditLogMapper;
    private final AnomalyEventMapper anomalyEventMapper;

    /**
     * 构造器注入（G-14）。
     *
     * @param dashboardService    概览看板服务（复用）
     * @param toolLogQueryService 工具日志查询服务（复用）
     * @param toolCallLogMapper   工具日志 Mapper（缺口聚合）
     * @param auditLogMapper      审计日志 Mapper（幻觉拦截 / 泄漏计数）
     * @param anomalyEventMapper  四层异常事件 Mapper（L1/L2 埋点，W1）
     */
    public MonitorService(DashboardService dashboardService,
                          ToolLogQueryService toolLogQueryService,
                          ToolCallLogMapper toolCallLogMapper,
                          AuditLogMapper auditLogMapper,
                          AnomalyEventMapper anomalyEventMapper) {
        this.dashboardService = dashboardService;
        this.toolLogQueryService = toolLogQueryService;
        this.toolCallLogMapper = toolCallLogMapper;
        this.auditLogMapper = auditLogMapper;
        this.anomalyEventMapper = anomalyEventMapper;
    }

    /**
     * 概览 KPI（FR-17 ①）。
     *
     * <p>复用 {@link DashboardService#summary()} 的今日/活跃/调用量/成功率/降级数，
     * 并补以上限的全量平均耗时（{@link ToolLogQueryService#stats}）。
     *
     * @return 概览
     */
    public Overview overview() {
        DashboardService.Summary summary = dashboardService.summary();
        ToolLogQueryService.Stats stats = toolLogQueryService.stats(null, null, null);
        return new Overview(summary.todayMessages(), summary.activeUsers(), summary.toolCalls(),
                summary.successRate(), stats.avgLatencyMs(), summary.degradedCount());
    }

    /**
     * 工具调用趋势（FR-17 ④：折线）。
     *
     * @param start       下界（可空）
     * @param end         上界（可空）
     * @param granularity 粒度：{@code HOUR} 为小时桶，其余按天桶
     * @return 按时间正序的趋势点
     */
    public List<TrendPoint> trend(LocalDateTime start, LocalDateTime end, String granularity) {
        String bucketExpr = "HOUR".equalsIgnoreCase(granularity) ? BUCKET_HOUR : BUCKET_DAY;
        QueryWrapper<ToolCallLogEntity> wrapper = new QueryWrapper<>();
        wrapper.select(bucketExpr + " AS bucket",
                        "COUNT(*) AS total",
                        "SUM(CASE WHEN status = 0 THEN 1 ELSE 0 END) AS success")
                .groupBy(bucketExpr)
                .orderByAsc("bucket");
        applyTimeRange(wrapper, start, end);
        List<Map<String, Object>> rows = toolCallLogMapper.selectMaps(wrapper);
        List<TrendPoint> points = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            long total = toLong(row.get("total"));
            long success = toLong(row.get("success"));
            points.add(new TrendPoint(toText(row.get("bucket")), total, success, rate(success, total)));
        }
        return points;
    }

    /**
     * 按工具名分组的成功率（FR-17 ④：柱状）。
     *
     * <p>复用 {@link ToolLogQueryService#stats} 的按工具分组明细，避免重复聚合。
     *
     * @param start 下界（可空）
     * @param end   上界（可空）
     * @return 按工具名分组的成功率
     */
    public List<SuccessRate> successRateByTool(LocalDateTime start, LocalDateTime end) {
        return toolLogQueryService.stats(null, start, end).items().stream()
                .map(item -> new SuccessRate(item.toolName(), item.total(), item.success(),
                        rate(item.success(), item.total())))
                .toList();
    }

    /**
     * 意图分布（FR-17 ④：饼图）。
     *
     * <p>口径：按 {@code log_tool_call.tool_name} 分组后归并到业务意图域（见 {@link #mapToolToIntent}）。
     * 系统未持久化 LLM 意图域标签，故以工具调用归因作为可用近似，未匹配工具名者归入 {@code chat}。
     *
     * @param start 下界（可空）
     * @param end   上界（可空）
     * @return 按固定意图域顺序的分布（缺失域记 0）
     */
    public List<IntentSlice> intentDistribution(LocalDateTime start, LocalDateTime end) {
        QueryWrapper<ToolCallLogEntity> wrapper = new QueryWrapper<>();
        wrapper.select("tool_name", "COUNT(*) AS total").groupBy("tool_name");
        applyTimeRange(wrapper, start, end);
        List<Map<String, Object>> rows = toolCallLogMapper.selectMaps(wrapper);

        Map<String, Long> byIntent = new LinkedHashMap<>();
        for (String intent : INTENT_ORDER) {
            byIntent.put(intent, 0L);
        }
        for (Map<String, Object> row : rows) {
            String intent = mapToolToIntent(toText(row.get("tool_name")));
            byIntent.merge(intent, toLong(row.get("total")), Long::sum);
        }
        return byIntent.entrySet().stream()
                .map(entry -> new IntentSlice(entry.getKey(), entry.getValue()))
                .toList();
    }

    /**
     * 延迟分布直方图（FR-17 ④）。
     *
     * <p>固定桶：{@code <100}、{@code 100-299}、{@code 300-999}、{@code 1000-2999}、{@code >=3000}（ms）。
     *
     * @param start 下界（可空）
     * @param end   上界（可空）
     * @return 固定 5 桶分布（无数据时各桶记 0）
     */
    public List<LatencyBucket> latencyDistribution(LocalDateTime start, LocalDateTime end) {
        QueryWrapper<ToolCallLogEntity> wrapper = new QueryWrapper<>();
        wrapper.select(
                "SUM(CASE WHEN latency_ms IS NOT NULL AND latency_ms < 100 THEN 1 ELSE 0 END) AS b0",
                "SUM(CASE WHEN latency_ms >= 100 AND latency_ms < 300 THEN 1 ELSE 0 END) AS b1",
                "SUM(CASE WHEN latency_ms >= 300 AND latency_ms < 1000 THEN 1 ELSE 0 END) AS b2",
                "SUM(CASE WHEN latency_ms >= 1000 AND latency_ms < 3000 THEN 1 ELSE 0 END) AS b3",
                "SUM(CASE WHEN latency_ms >= 3000 THEN 1 ELSE 0 END) AS b4");
        applyTimeRange(wrapper, start, end);
        List<Map<String, Object>> rows = toolCallLogMapper.selectMaps(wrapper);

        Map<String, Object> row = rows.isEmpty() ? Map.of() : rows.get(0);
        List<String> ranges = List.of("<100", "100-299", "300-999", "1000-2999", ">=3000");
        List<LatencyBucket> buckets = new ArrayList<>();
        for (int i = 0; i < ranges.size(); i++) {
            buckets.add(new LatencyBucket(ranges.get(i), toLong(row.get("b" + i))));
        }
        return buckets;
    }

    /**
     * 降级与拦截看板指标（A-3 / T5）。
     *
     * @param start 下界（可空）
     * @param end   上界（可空）
     * @return 降级触发率 / 幻觉拦截 / 四层异常分布 / 对外泄漏 KPI
     */
    public DegradeMetrics degradeMetrics(LocalDateTime start, LocalDateTime end) {
        long total = countByStatus(start, end, null);
        long degraded = countByStatus(start, end, ToolStatus.DEGRADED);
        long timeout = countByStatus(start, end, ToolStatus.TIMEOUT);
        long degradedCalls = degraded + timeout;
        long hallu = countAudit(ACTION_HALLUCINATION);
        long leak = countAudit(ACTION_EXTERNAL_LEAK);
        return new DegradeMetrics(total, degradedCalls, degraded, timeout,
                rate(degradedCalls, total), hallu, leak, leak == 0,
                anomalyDistribution(start, end));
    }

    /**
     * 四层异常分布（对齐 SRS 2.3.5）。
     *
     * <p>两个事实来源合并计数：
     * <ul>
     *   <li>{@code log_tool_call.error_type} 经 {@link #classifyLayer} 归并——工具层调用产生的异常，
     *       映射对 L1/L2/L4 码同样兼容；</li>
     *   <li>{@code log_anomaly_event.layer} <b>显式层次</b>直接入库（迭代 4 W1）——补齐此前
     *       只有一行 WARN、看板恒为 0 的 <b>L1 接入层 / L2 认知层</b>。</li>
     * </ul>
     *
     * <p>注：{@code log_anomaly_event} 的层次不靠错误码前缀推断，故 {@code MSG_DUPLICATED} 这类
     * 无法被前缀启发式识别的 L1 码也能正确归层。
     *
     * @param start 下界（可空）
     * @param end   上界（可空）
     * @return 固定 L1~L4 顺序的分布（缺失层记 0）
     */
    public List<AnomalyLayer> anomalyDistribution(LocalDateTime start, LocalDateTime end) {
        QueryWrapper<ToolCallLogEntity> wrapper = new QueryWrapper<>();
        wrapper.select("error_type", "COUNT(*) AS total")
                .isNotNull("error_type")
                .ne("error_type", "")
                .groupBy("error_type");
        applyTimeRange(wrapper, start, end);
        List<Map<String, Object>> rows = toolCallLogMapper.selectMaps(wrapper);

        Map<String, Long> byLayer = new LinkedHashMap<>();
        for (String layer : LAYER_ORDER) {
            byLayer.put(layer, 0L);
        }
        for (Map<String, Object> row : rows) {
            String layer = classifyLayer(toText(row.get("error_type")));
            if (layer != null) {
                byLayer.merge(layer, toLong(row.get("total")), Long::sum);
            }
        }

        QueryWrapper<AnomalyEventEntity> anomalyWrapper = new QueryWrapper<>();
        anomalyWrapper.select("layer", "COUNT(*) AS total")
                .isNotNull("layer")
                .groupBy("layer");
        applyTimeRange(anomalyWrapper, start, end);
        for (Map<String, Object> row : anomalyEventMapper.selectMaps(anomalyWrapper)) {
            String layer = toText(row.get("layer"));
            if (layer != null && byLayer.containsKey(layer)) {
                byLayer.merge(layer, toLong(row.get("total")), Long::sum);
            }
        }

        List<AnomalyLayer> result = new ArrayList<>();
        for (String layer : LAYER_ORDER) {
            result.add(new AnomalyLayer(layer, LAYER_LABELS.get(layer), byLayer.get(layer)));
        }
        return result;
    }

    private long countByStatus(LocalDateTime start, LocalDateTime end, ToolStatus status) {
        LambdaQueryWrapper<ToolCallLogEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(status != null, ToolCallLogEntity::getStatus, status == null ? null : status.getCode())
                .ge(start != null, ToolCallLogEntity::getCreatedAt, start)
                .le(end != null, ToolCallLogEntity::getCreatedAt, end);
        Long count = toolCallLogMapper.selectCount(wrapper);
        return count == null ? 0L : count;
    }

    private long countAudit(String action) {
        Long count = auditLogMapper.selectCount(new LambdaQueryWrapper<AuditLogEntity>()
                .eq(AuditLogEntity::getRegType, REG_TYPE_SAFETY)
                .eq(AuditLogEntity::getAction, action));
        return count == null ? 0L : count;
    }

    private static void applyTimeRange(QueryWrapper<?> wrapper,
                                       LocalDateTime start, LocalDateTime end) {
        if (start != null) {
            wrapper.ge("created_at", start);
        }
        if (end != null) {
            wrapper.le("created_at", end);
        }
    }

    /**
     * 工具名 → 业务意图域（可用归因口径，见 {@link #intentDistribution}）。
     *
     * @param toolName 工具名（可空）
     * @return 意图域标签；未匹配归入 {@code chat}
     */
    private static String mapToolToIntent(String toolName) {
        if (toolName == null) {
            return "chat";
        }
        return switch (toolName) {
            case "recognize_image" -> "image_recognition";
            case "synthesize_voice" -> "tts";
            case "query_express" -> "express";
            case "plan_route" -> "navigation";
            case "manage_pet_profile" -> "pet_profile";
            default -> "chat";
        };
    }

    /**
     * 异常错误码 → 四层分类（对齐 SRS 2.3.5）。
     *
     * @param errorType 错误码（可空）
     * @return L1/L2/L3/L4；无法归类返回 null（不计入分布）
     */
    private static String classifyLayer(String errorType) {
        if (errorType == null || errorType.isBlank()) {
            return null;
        }
        String code = errorType.toUpperCase(Locale.ROOT);
        if (code.startsWith("SIGNATURE") || code.startsWith("DUPLICATE")
                || code.startsWith("ACCESS") || code.startsWith("ILLEGAL")) {
            return "L1";
        }
        if (code.startsWith("LLM") || code.startsWith("PROTOCOL") || code.startsWith("INTENT")
                || code.contains("CONFIDENCE")) {
            return "L2";
        }
        if (code.contains("HALLUCINATION") || code.startsWith("CONTENT")
                || code.startsWith("SAFETY") || code.startsWith("SEND")) {
            return "L4";
        }
        // 工具层（默认）：TOOL_* / INVALID_ARGS / NOT_EXECUTED / PARAM_* 等
        return "L3";
    }

    private static double rate(long part, long total) {
        return total == 0 ? 0.0 : (double) part / total;
    }

    private static String toText(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    /**
     * 监控概览 KPI。
     *
     * @param todayMessages 今日消息量
     * @param activeUsers   活跃用户数
     * @param toolCalls     工具调用总量
     * @param successRate   成功率（0~1）
     * @param avgLatencyMs  平均耗时（ms）
     * @param degradedCount 降级次数
     */
    public record Overview(long todayMessages, long activeUsers, long toolCalls,
                           double successRate, double avgLatencyMs, long degradedCount) {
    }

    /**
     * 趋势点。
     *
     * @param bucket      时间桶
     * @param total       调用量
     * @param success     成功数
     * @param successRate 成功率（0~1）
     */
    public record TrendPoint(String bucket, long total, long success, double successRate) {
    }

    /**
     * 按工具名的成功率。
     *
     * @param toolName    工具名
     * @param total       调用量
     * @param success     成功数
     * @param successRate 成功率（0~1）
     */
    public record SuccessRate(String toolName, long total, long success, double successRate) {
    }

    /**
     * 意图分布切片。
     *
     * @param intent 意图域
     * @param count  调用量
     */
    public record IntentSlice(String intent, long count) {
    }

    /**
     * 延迟分布桶。
     *
     * @param range 区间标签（ms）
     * @param count 计数
     */
    public record LatencyBucket(String range, long count) {
    }

    /**
     * 四层异常分布项。
     *
     * @param layer 层次（L1~L4）
     * @param label 中文名
     * @param count 计数
     */
    public record AnomalyLayer(String layer, String label, long count) {
    }

    /**
     * 降级与拦截看板指标。
     *
     * @param totalCalls                 调用总量
     * @param degradedCalls              降级触发数（status=2 与 3 之和）
     * @param degradedCount              降级数（status=2）
     * @param timeoutCount               超时数（status=3）
     * @param degradeRate                降级触发率（0~1）
     * @param hallucinationInterceptions 执行性幻觉拦截次数
     * @param externalLeakCount          对外泄漏次数
     * @param leakKpiPass                泄漏 KPI 是否达标
     * @param anomalyDistribution        四层异常分布
     */
    public record DegradeMetrics(long totalCalls, long degradedCalls, long degradedCount,
                                 long timeoutCount, double degradeRate, long hallucinationInterceptions,
                                 long externalLeakCount, boolean leakKpiPass,
                                 List<AnomalyLayer> anomalyDistribution) {
    }
}
