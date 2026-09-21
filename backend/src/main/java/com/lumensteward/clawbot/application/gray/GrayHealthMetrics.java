package com.lumensteward.clawbot.application.gray;

/**
 * 灰度熔断的健康度取数端口（FR-22 / W2）。
 *
 * <p>依赖铁律（NFR-MA-03）：端口在 application 层，实现落在 infrastructure 层，
 * 熔断逻辑不直接触碰 Mapper。
 */
public interface GrayHealthMetrics {

    /**
     * 统计最近窗口的链路轮次、异常数与 P95 时延。
     *
     * @param windowMinutes 窗口分钟数（&lt;=0 时由实现按默认窗口处理）
     * @return 健康度快照；无数据返回全零快照
     */
    GrayHealthSnapshot measure(int windowMinutes);
}
