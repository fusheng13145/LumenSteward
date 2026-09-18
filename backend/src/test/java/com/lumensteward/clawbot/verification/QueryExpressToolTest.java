package com.lumensteward.clawbot.verification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lumensteward.clawbot.common.util.JsonUtils;
import com.lumensteward.clawbot.domain.port.ExpressQueryPort;
import com.lumensteward.clawbot.domain.port.ExpressQueryException;
import com.lumensteward.clawbot.domain.port.model.ExpressTrace;
import com.lumensteward.clawbot.domain.tool.ToolContext;
import com.lumensteward.clawbot.domain.tool.ToolResult;
import com.lumensteward.clawbot.domain.tool.impl.QueryExpressTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link QueryExpressTool} 单元测试（FR-12 / SRS）。
 *
 * <p>覆盖：成功查询、未查到返回 EMPTY_RESULT 不编造、运单号前缀推断承运商、参数缺失。
 */
class QueryExpressToolTest {

    private static final ToolContext CTX = ToolContext.of("trace-q", "openid-q", 1L, 1);

    private static ExpressTrace trace(boolean found, String state) {
        return new ExpressTrace("SF", "SF123", state, found,
                List.of(new ExpressTrace.Node("2026-01-01 10:00", "已揽收", "已揽收")));
    }

    @Test
    @DisplayName("FR-12：成功查询 → 返回轨迹节点")
    void shouldReturnTraceOnSuccess() {
        ExpressQueryPort port = (company, no) -> trace(true, "运输中");
        QueryExpressTool tool = new QueryExpressTool(port);

        ToolResult result = tool.execute(CTX, args("SF1234567890", null));

        assertThat(result.isSuccess()).isTrue();
        JsonNode data = result.data();
        assertThat(data.get("company").asText()).isEqualTo("SF");
        assertThat(data.get("state").asText()).isEqualTo("运输中");
        assertThat(data.get("nodes").isArray()).isTrue();
    }

    @Test
    @DisplayName("FR-12：未查到 → EMPTY_RESULT，不编造（BR-09）")
    void shouldReturnEmptyWhenNotFound() {
        ExpressQueryPort port = (company, no) -> trace(false, "无数据");
        QueryExpressTool tool = new QueryExpressTool(port);

        ToolResult result = tool.execute(CTX, args("SF123", null));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errorType()).isEqualTo("EMPTY_RESULT");
    }

    @Test
    @DisplayName("FR-12：运单号前缀推断承运商（圆通 YT → YTO）")
    void shouldInferCarrierFromPrefix() {
        ExpressQueryPort port = (company, no) -> {
            assertThat(company).isEqualTo("YTO");
            return trace(true, "运输中");
        };
        QueryExpressTool tool = new QueryExpressTool(port);

        ToolResult result = tool.execute(CTX, args("YT9988776655", null));

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("FR-12：缺少运单号 → INVALID_ARGS")
    void shouldRejectMissingTrackingNo() {
        ExpressQueryPort port = (company, no) -> trace(true, "运输中");
        QueryExpressTool tool = new QueryExpressTool(port);

        ToolResult result = tool.execute(CTX, args(null, null));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errorType()).isEqualTo("INVALID_ARGS");
    }

    @Test
    @DisplayName("FR-12：端口异常 → 降级为 EXPRESS_FAILED")
    void shouldDegradeOnPortException() {
        ExpressQueryPort port = (company, no) -> {
            throw new ExpressQueryException("上游不可用");
        };
        QueryExpressTool tool = new QueryExpressTool(port);

        ToolResult result = tool.execute(CTX, args("SF123", null));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errorType()).isEqualTo("EXPRESS_FAILED");
    }

    private static JsonNode args(String trackingNo, String companyCode) {
        ObjectNode node = JsonUtils.mapper().createObjectNode();
        if (trackingNo != null) {
            node.put("tracking_no", trackingNo);
        }
        if (companyCode != null) {
            node.put("company_code", companyCode);
        }
        return node;
    }
}
