package com.lumensteward.clawbot.infrastructure.client.map.model;

/**
 * 路线规划结果（SRS FR-13 / 附录 B-4）。
 *
 * @param distanceMeters 距离（米）
 * @param durationSeconds 预计耗时（秒）
 * @param mode           出行方式
 * @param summary        路线摘要
 */
public record RouteResult(double distanceMeters, long durationSeconds, RouteMode mode, String summary) {
}
