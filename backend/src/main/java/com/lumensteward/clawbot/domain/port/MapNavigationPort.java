package com.lumensteward.clawbot.domain.port;

import com.lumensteward.clawbot.domain.port.model.GeoPoint;
import com.lumensteward.clawbot.domain.port.model.RouteMode;
import com.lumensteward.clawbot.domain.port.model.RouteResult;

/**
 * 地图导航能力端口（SRS FR-13）。
 *
 * <p>上提自 {@code infrastructure/client/map}，由 {@code MockMapClient} /
 * {@code RealMapClient} 实现（TODO-04）。
 */
public interface MapNavigationPort {

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
