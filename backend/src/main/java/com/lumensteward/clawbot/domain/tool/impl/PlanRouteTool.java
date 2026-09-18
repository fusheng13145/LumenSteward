package com.lumensteward.clawbot.domain.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lumensteward.clawbot.common.util.JsonUtils;
import com.lumensteward.clawbot.domain.port.MapNavigationPort;
import com.lumensteward.clawbot.domain.port.model.GeoPoint;
import com.lumensteward.clawbot.domain.port.model.RouteMode;
import com.lumensteward.clawbot.domain.port.model.RouteResult;
import com.lumensteward.clawbot.domain.tool.JsonSchema;
import com.lumensteward.clawbot.domain.tool.Tool;
import com.lumensteward.clawbot.domain.tool.ToolContext;
import com.lumensteward.clawbot.domain.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 导航工具（架构 5.1 / SRS FR-13，name=plan_route）。
 *
 * <p>{@code idempotent=true}、{@code critical=false}。地理编码 → 路线规划 → 产出可跳转链接
 * （移动端可开）。<b>坐标仅临时使用、不落库原始坐标</b>（BR-17，PRD FR-13 验收④）。失败返回
 * "暂时无法规划路线"，不编造路线。
 */
@Component
public class PlanRouteTool implements Tool {

    /** 工具名（全局唯一，FR-23）。 */
    public static final String NAME = "plan_route";

    private static final Logger log = LoggerFactory.getLogger(PlanRouteTool.class);

    private static final String DESCRIPTION =
            "规划两地之间的导航路线并生成可点击的地图链接。适用于用户询问'怎么去某地'、'导航到公司'等场景。";

    private static final String PARAMETERS_SCHEMA = """
            {"type":"object","properties":{
              "origin":{"type":"string","description":"起点地址"},
              "destination":{"type":"string","description":"终点地址"},
              "mode":{"type":"string","enum":["driving","walking","riding","transit"],"description":"出行方式，默认 driving"}
            },"required":["origin","destination"]}
            """;

    /** 腾讯地图 routeplan 的 type 取值映射。 */
    private static final java.util.Map<RouteMode, String> MODE_TO_TENCENT = java.util.Map.of(
            RouteMode.DRIVING, "drive",
            RouteMode.WALKING, "walk",
            RouteMode.RIDING, "ride",
            RouteMode.TRANSIT, "bus");

    private final MapNavigationPort mapNavigationPort;
    private final JsonSchema schema;

    /**
     * 构造器注入（G-14）。
     *
     * @param mapNavigationPort 地图导航端口
     */
    public PlanRouteTool(MapNavigationPort mapNavigationPort) {
        this.mapNavigationPort = mapNavigationPort;
        this.schema = JsonSchema.of(PARAMETERS_SCHEMA);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return DESCRIPTION;
    }

    @Override
    public JsonSchema parametersSchema() {
        return schema;
    }

    @Override
    public boolean idempotent() {
        return true;
    }

    @Override
    public boolean critical() {
        return false;
    }

    @Override
    public ToolResult execute(ToolContext context, JsonNode args) {
        long start = System.currentTimeMillis();
        String origin = text(args, "origin");
        String destination = text(args, "destination");
        if ((origin == null || origin.isBlank()) || (destination == null || destination.isBlank())) {
            return ToolResult.failure("INVALID_ARGS", "缺少起点 origin 或终点 destination", false);
        }
        RouteMode mode = RouteMode.fromWire(text(args, "mode"));
        GeoPoint from = mapNavigationPort.geocode(origin);
        GeoPoint to = mapNavigationPort.geocode(destination);
        if (from == null || to == null) {
            return ToolResult.failure("INVALID_ARGS", "无法解析地址，请确认起点或终点是否正确", false);
        }
        RouteResult route = mapNavigationPort.planRoute(from, to, mode);
        if (route == null) {
            // 规划失败：返回友好提示，不编造路线
            return ToolResult.failure("ROUTE_FAILED", "暂时无法规划路线，请稍后重试", false);
        }
        String mapUrl = buildMapUrl(to, mode, destination);
        ObjectNode data = JsonUtils.mapper().createObjectNode();
        data.put("mode", mode.wire());
        data.put("distance_meters", route.distanceMeters());
        data.put("duration_seconds", route.durationSeconds());
        data.put("summary", route.summary());
        data.put("map_url", mapUrl);
        return ToolResult.success(data, elapsed(start));
    }

    /**
     * 构造可跳转的地图链接（坐标仅临时用于拼 URL，不落库，BR-17）。
     *
     * @param to           终点坐标（GCJ-02）
     * @param mode         出行方式
     * @param destLabel    终点名称（用于链接展示）
     * @return 地图 routeplan URL
     */
    private static String buildMapUrl(GeoPoint to, RouteMode mode, String destLabel) {
        String type = MODE_TO_TENCENT.getOrDefault(mode, "drive");
        String label = destLabel == null ? "" : destLabel;
        String encodedLabel = URLEncoder.encode(label, StandardCharsets.UTF_8).replace("+", "%20");
        String coord = to.latitude() + "," + to.longitude();
        return "https://apis.map.qq.com/uri_v1/routeplan?type=" + type
                + "&to=" + encodedLabel
                + "&tocoord=" + coord
                + "&referer=clawbot";
    }

    private static long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }

    private static String text(JsonNode args, String field) {
        if (args == null || !args.hasNonNull(field)) {
            return null;
        }
        return args.get(field).asText();
    }
}
