package com.lumensteward.clawbot.infrastructure.client.map;

import com.lumensteward.clawbot.infrastructure.client.map.model.GeoPoint;
import com.lumensteward.clawbot.infrastructure.client.map.model.RouteMode;
import com.lumensteward.clawbot.infrastructure.client.map.model.RouteResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 地图服务 Mock 实现（SRS FR-13，AC-D1：无外网）。
 *
 * <p>返回确定性坐标与路线；不落库原始坐标（BR-17）。
 */
@Component
public class MockMapClient implements MapClient {

    private static final Logger log = LoggerFactory.getLogger(MockMapClient.class);

    @Override
    public GeoPoint geocode(String address) {
        if (address == null || address.isBlank()) {
            return null;
        }
        log.info("Mock 地理编码 address={}", address);
        // 确定性伪坐标（GCJ-02），仅用于联调
        int hash = Math.abs(address.hashCode());
        double lng = 116.30 + (hash % 1000) / 10000.0;
        double lat = 39.90 + (hash % 777) / 10000.0;
        return new GeoPoint(lng, lat, address);
    }

    @Override
    public RouteResult planRoute(GeoPoint from, GeoPoint to, RouteMode mode) {
        if (from == null || to == null) {
            return null;
        }
        RouteMode effectiveMode = mode == null ? RouteMode.DRIVING : mode;
        double distance = haversine(from, to);
        long duration = Math.round(distance / 1000.0 / speedKmh(effectiveMode) * 3600.0);
        log.info("Mock 路线规划 mode={} distance={}m duration={}s",
                effectiveMode.wire(), Math.round(distance), duration);
        return new RouteResult(distance, duration, effectiveMode,
                "沿主路行驶约 " + Math.round(distance / 1000.0) + " 公里");
    }

    private static double speedKmh(RouteMode mode) {
        return switch (mode) {
            case WALKING -> 5.0;
            case RIDING -> 15.0;
            case TRANSIT -> 25.0;
            case DRIVING -> 45.0;
        };
    }

    private static double haversine(GeoPoint a, GeoPoint b) {
        double earth = 6371000.0;
        double dLat = Math.toRadians(b.latitude() - a.latitude());
        double dLng = Math.toRadians(b.longitude() - a.longitude());
        double lat1 = Math.toRadians(a.latitude());
        double lat2 = Math.toRadians(b.latitude());
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * earth * Math.asin(Math.min(1.0, Math.sqrt(h)));
    }
}
