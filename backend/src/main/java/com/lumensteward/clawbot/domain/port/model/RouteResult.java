package com.lumensteward.clawbot.domain.port.model;

/**
 * 路线规划结果（领域端口 DTO，上提自 {@code infrastructure/client/map/model}）。
 *
 * @param distanceMeters  距离（米）
 * @param durationSeconds 预计耗时（秒）
 * @param mode            出行方式
 * @param summary         路线摘要
 */
public record RouteResult(double distanceMeters, long durationSeconds, RouteMode mode, String summary) {
}
