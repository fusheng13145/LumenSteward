package com.lumensteward.clawbot.verification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lumensteward.clawbot.common.util.JsonUtils;
import com.lumensteward.clawbot.domain.tool.ToolContext;
import com.lumensteward.clawbot.domain.tool.ToolResult;
import com.lumensteward.clawbot.domain.tool.impl.PlanRouteTool;
import com.lumensteward.clawbot.infrastructure.client.map.MockMapClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link PlanRouteTool} 单元测试（FR-13 / SRS）。
 *
 * <p>覆盖：成功规划并返回可跳转链接、参数缺失、地址无法解析（坐标临时使用不落库，BR-17）。
 */
class PlanRouteToolTest {

    private static final ToolContext CTX = ToolContext.of("trace-r", "openid-r", 1L, 1);
    private final PlanRouteTool tool = new PlanRouteTool(new MockMapClient());

    @Test
    @DisplayName("FR-13：成功规划 → 返回地图可跳转链接")
    void shouldPlanRouteWithMapLink() {
        ToolResult result = tool.execute(CTX, args("公司", "人民广场", null));

        assertThat(result.isSuccess()).isTrue();
        JsonNode data = result.data();
        assertThat(data.get("map_url").asText()).contains("apis.map.qq.com/uri_v1/routeplan");
        assertThat(data.get("distance_meters").asLong()).isPositive();
    }

    @Test
    @DisplayName("FR-13：出行方式透传（walking → walk）")
    void shouldHonorMode() {
        ToolResult result = tool.execute(CTX, args("家", "公司", "walking"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.data().get("map_url").asText()).contains("type=walk");
    }

    @Test
    @DisplayName("FR-13：缺少起点 → INVALID_ARGS")
    void shouldRejectMissingOrigin() {
        ToolResult result = tool.execute(CTX, args(null, "人民广场", null));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errorType()).isEqualTo("INVALID_ARGS");
    }

    @Test
    @DisplayName("FR-13：地址无法解析 → 友好失败，不编造路线")
    void shouldFailWhenAddressUnresolvable() {
        // MockMapClient 对空白地址返回 null
        ToolResult result = tool.execute(CTX, args("   ", "人民广场", null));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errorType()).isEqualTo("INVALID_ARGS");
    }

    private static JsonNode args(String origin, String destination, String mode) {
        ObjectNode node = JsonUtils.mapper().createObjectNode();
        if (origin != null) {
            node.put("origin", origin);
        }
        if (destination != null) {
            node.put("destination", destination);
        }
        if (mode != null) {
            node.put("mode", mode);
        }
        return node;
    }
}
