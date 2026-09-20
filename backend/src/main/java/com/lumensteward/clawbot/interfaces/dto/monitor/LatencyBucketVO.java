package com.lumensteward.clawbot.interfaces.dto.monitor;

/**
 * 延迟分布直方桶（FR-17 ④：延迟分布直方图 / T4）。
 *
 * @param range 桶区间标签（如 {@code <100}、{@code 100-299}、{@code >=3000}，单位 ms）
 * @param count 落入该区间的调用数
 */
public record LatencyBucketVO(String range,
                              long count) {
}
