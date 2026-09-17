package com.lumensteward.clawbot.infrastructure.client.map.model;

/**
 * 地理坐标点（SRS FR-13）。
 *
 * <p>遵循 GCJ-02 坐标系（BR-16）；位置信息仅临时使用、不落库原始坐标（BR-17）。
 *
 * @param longitude 经度
 * @param latitude  纬度
 * @param label     地点名称（可空）
 */
public record GeoPoint(double longitude, double latitude, String label) {

    /** 便捷构造：无名称。 */
    public static GeoPoint of(double longitude, double latitude) {
        return new GeoPoint(longitude, latitude, null);
    }
}
