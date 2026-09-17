package com.lumensteward.clawbot.infrastructure.client.map;

import com.lumensteward.clawbot.infrastructure.client.map.model.GeoPoint;
import com.lumensteward.clawbot.infrastructure.client.map.model.RouteMode;
import com.lumensteward.clawbot.infrastructure.client.map.model.RouteResult;

/**
 * 地图服务 SPI（架构 5.1 / SRS FR-13）。
 *
 * <p><b>注意（BR-04）：</b>与物流 SPI 同理，本接口及其 Mock 实现<b>不</b>实现 {@code Tool} 接口，
 * 因而不会被注册、也不会下发模型。
 */
public interface MapClient {

    /**
     * 地理编码（地址 → 坐标）。
     *
     * @param address 地址
     * @return 坐标；解析失败返回 null
     */
    GeoPoint geocode(String address);

    /**
     * 路线规划。
     *
     * @param from 起点
     * @param to   终点
     * @param mode 出行方式
     * @return 路线结果；失败返回 null
     */
    RouteResult planRoute(GeoPoint from, GeoPoint to, RouteMode mode);
}
