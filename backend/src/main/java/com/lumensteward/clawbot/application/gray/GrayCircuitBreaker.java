package com.lumensteward.clawbot.application.gray;

import com.lumensteward.clawbot.application.config.ConfigKeys;
import com.lumensteward.clawbot.application.config.DynamicConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 灰度熔断回滚守护（FR-22 异常流 3a / BR-31）。
 *
 * <p>每 30s 评估一次近窗健康度（<b>≤ 60s 满足 AC②</b>；调度器单线程，任务本身只跑两条聚合查询）。
 * 判据为「窗口内 L2~L4 异常数 / 链路轮次」与「链路 P95 时延」双阈值，任一越界即把<b>全部</b>
 * 在放量的灰度功能比例置 0，并写 {@code reg_type=GRAY} 审计与 ERROR 告警日志。
 *
 * <p><b>口径的边界（如实声明）：</b>异常与链路表只存<b>脱敏</b> openid（BR-21），且异常未按
 * 功能归因，因此熔断是<b>全局健康度杀开关</b>而非「谁坏了关谁」。这与 BR-31「有明确回滚路径」
 * 的要求一致（宁可误关不可漏关），但不等于细粒度归因，后者需要按功能埋点（未做，见手册缺口）。
 *
 * <p><b>样本不足不回滚</b>：窗口内链路轮次 &lt; {@code gray.breaker.min-samples} 时跳过判定，
 * 避免低峰期一条异常就把放量清零（误熔断比不放量更伤）。
 */
@Component
public class GrayCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(GrayCircuitBreaker.class);

    /** 评估间隔（ms）：30s，配合单次评估耗时仍远小于 FR-22 的 60s 上限；后台状态端点原样透出。 */
    public static final long CHECK_INTERVAL_MS = 30_000L;

    private final DynamicConfigService dynamicConfig;
    private final GrayHealthMetrics metrics;
    private final GrayRollbackService rollbackService;

    /**
     * 构造器注入（G-14）。
     *
     * @param dynamicConfig   动态配置源（阈值与开关）
     * @param metrics         窗口健康度取数端口
     * @param rollbackService 回滚执行
     */
    public GrayCircuitBreaker(DynamicConfigService dynamicConfig,
                              GrayHealthMetrics metrics,
                              GrayRollbackService rollbackService) {
        this.dynamicConfig = dynamicConfig;
        this.metrics = metrics;
        this.rollbackService = rollbackService;
    }

    /** 定时评估入口。 */
    @Scheduled(initialDelay = CHECK_INTERVAL_MS, fixedDelay = CHECK_INTERVAL_MS)
    public void scheduledEvaluate() {
        try {
            evaluate();
        } catch (RuntimeException e) {
            // 熔断守护自身失败不得冒泡到调度线程池（对齐「配置读取永不抛异常」的取向）
            log.error("灰度熔断评估异常（跳过本轮）: err={}", e.getMessage(), e);
        }
    }

    /**
     * 执行一轮评估（公开以便后台展示判据与单测直接驱动）。
     *
     * @return 本轮结论
     */
    public Evaluation evaluate() {
        Thresholds thresholds = thresholds();
        if (!thresholds.enabled()) {
            return new Evaluation(true, "熔断开关关闭", null, false, null, 0);
        }
        GrayHealthSnapshot snapshot = metrics.measure(thresholds.windowMinutes());
        if (snapshot.turns() < thresholds.minSamples()) {
            return new Evaluation(true, "窗口样本不足: " + snapshot.turns()
                    + " < " + thresholds.minSamples(), snapshot, false, null, 0);
        }
        String cause = breachCause(snapshot, thresholds);
        if (cause == null) {
            return new Evaluation(false, null, snapshot, false, null, 0);
        }
        GrayRollbackService.RollbackResult result = rollbackService.rollbackAll(
                "熔断自动回滚：" + cause + "（窗口 " + snapshot.windowMinutes() + " 分钟，轮次 "
                        + snapshot.turns() + "，异常 " + snapshot.anomalies() + "）", null, null);
        if (result.rolledBack() > 0) {
            log.error("灰度熔断触发：{} —— 已自动回滚 {} 项灰度（{}s 内生效），请核查近窗异常后重新放量",
                    cause, result.rolledBack(), CHECK_INTERVAL_MS / 1000);
        } else {
            // 已回滚过且无在放量功能：越界窗口未过去时每轮都会读到同一判据，
            // 若仍打 ERROR 就是每 30s 一条重复告警（告警疲劳会让真告警被忽略），故降为 WARN。
            log.warn("灰度熔断判据仍越界（{}），但已无在放量功能可回滚，不重复告警", cause);
        }
        return new Evaluation(false, null, snapshot, true, cause, result.rolledBack());
    }

    /**
     * 越界判据。
     *
     * @param snapshot   窗口健康度
     * @param thresholds 阈值
     * @return 越界原因；未越界返回 null
     */
    private static String breachCause(GrayHealthSnapshot snapshot, Thresholds thresholds) {
        if (snapshot.errorRatePercent() != null
                && snapshot.errorRatePercent().compareTo(BigDecimal.valueOf(thresholds.errorRatePercent())) > 0) {
            return "错误率 " + snapshot.errorRatePercent().toPlainString() + "% > "
                    + thresholds.errorRatePercent() + "%";
        }
        if (thresholds.p95Ms() > 0 && snapshot.p95LatencyMs() > thresholds.p95Ms()) {
            return "P95 时延 " + snapshot.p95LatencyMs() + "ms > " + thresholds.p95Ms() + "ms";
        }
        return null;
    }

    /**
     * 当前熔断阈值与开关（后台观测台展示用）。
     *
     * @return 阈值快照
     */
    public Thresholds thresholds() {
        return new Thresholds(
                dynamicConfig.getBoolean(ConfigKeys.GRAY_BREAKER_ENABLED, true),
                dynamicConfig.getInt(ConfigKeys.GRAY_BREAKER_WINDOW_MINUTES, 5),
                dynamicConfig.getInt(ConfigKeys.GRAY_BREAKER_MIN_SAMPLES, 20),
                dynamicConfig.getInt(ConfigKeys.GRAY_BREAKER_ERROR_RATE_PERCENT, 30),
                dynamicConfig.getInt(ConfigKeys.GRAY_BREAKER_P95_MS, 0));
    }

    /**
     * 熔断阈值。
     *
     * @param enabled            是否启用自动熔断
     * @param windowMinutes      观测窗口（分钟）
     * @param minSamples         最小样本轮次数
     * @param errorRatePercent   错误率阈值（百分比）
     * @param p95Ms              P95 时延阈值（0 表示不启用时延判据）
     */
    public record Thresholds(boolean enabled, int windowMinutes, int minSamples,
                             int errorRatePercent, int p95Ms) {
    }

    /**
     * 一轮评估结论。
     *
     * @param skipped     是否跳过判定
     * @param skipReason  跳过原因
     * @param snapshot    窗口健康度（跳过且未取样时为空）
     * @param breached    是否越界
     * @param breachCause 越界原因
     * @param rolledBack  被置 0 的灰度功能数
     */
    public record Evaluation(boolean skipped, String skipReason, GrayHealthSnapshot snapshot,
                             boolean breached, String breachCause, int rolledBack) {
    }
}
