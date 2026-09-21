package com.lumensteward.clawbot.verification;

import com.fasterxml.jackson.databind.JsonNode;
import com.lumensteward.clawbot.common.util.JsonUtils;
import com.lumensteward.clawbot.domain.tool.ToolContext;
import com.lumensteward.clawbot.domain.tool.ToolResult;
import com.lumensteward.clawbot.domain.tool.impl.QueryWeatherTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 示例插件工具单测（SRS FR-23 验收① 的样例载体）。
 *
 * <p>QA 视角只盯两件事：<b>无外网也不编造</b>（未收录城市给 EMPTY_RESULT，成功结果带
 * {@code data_source=mock-demo} 标注，BR-09）；<b>缺参走结构化失败</b>而非抛异常（BR-10），
 * 以便 FR-24 据 {@code required} 差集建立追问。
 */
class QueryWeatherToolTest {

    private static final ToolContext CTX = ToolContext.of("trace-weather", "openid-weather", 1L, 1);

    private final QueryWeatherTool tool = new QueryWeatherTool();

    private static JsonNode args(String json) {
        return JsonUtils.readTree(json);
    }

    @Test
    @DisplayName("元信息：幂等、非关键、只读（回放可安全干跑）")
    void declaresConservativeMetadata() {
        assertThat(tool.idempotent()).isTrue();
        assertThat(tool.critical()).isFalse();
        assertThat(tool.readOnly()).isTrue();
        assertThat(tool.monitorDomain()).isEqualTo("weather");
        assertThat(tool.taskIntent()).isNull();
    }

    @Test
    @DisplayName("样例城市命中 → 成功且结果自带 mock 标注")
    void returnsDemoForecastWithLabel() {
        ToolResult result = tool.execute(CTX, args("{\"city\":\"杭州\",\"date\":\"2026-09-21\"}"));

        assertThat(result.isSuccess()).isTrue();
        JsonNode data = result.data();
        assertThat(data.get("city").asText()).isEqualTo("杭州");
        assertThat(data.get("date").asText()).isEqualTo("2026-09-21");
        assertThat(data.get("data_source").asText()).isEqualTo("mock-demo");
        assertThat(result.latencyMs()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    @DisplayName("未收录城市 → EMPTY_RESULT 并说明支持范围，不编造（BR-09）")
    void unknownCityReturnsEmptyResult() {
        ToolResult result = tool.execute(CTX, args("{\"city\":\"拉萨\"}"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errorType()).isEqualTo("EMPTY_RESULT");
        assertThat(result.message()).contains("拉萨").contains("杭州、北京、上海");
    }

    @Test
    @DisplayName("缺 city / 入参为空 → INVALID_ARGS，结构化回注不抛异常（BR-10）")
    void missingCityFailsAsInvalidArgs() {
        assertThat(tool.execute(CTX, args("{}")).errorType()).isEqualTo("INVALID_ARGS");
        assertThat(tool.execute(CTX, args("{\"city\":\"  \"}")).errorType()).isEqualTo("INVALID_ARGS");
        assertThat(tool.execute(null, null).errorType()).isEqualTo("INVALID_ARGS");
    }

    @Test
    @DisplayName("date 缺省时以「今天」占位，不虚构具体日期")
    void defaultsDateLabel() {
        ToolResult result = tool.execute(CTX, args("{\"city\":\"北京\"}"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.data().get("date").asText()).isEqualTo("今天");
    }
}
