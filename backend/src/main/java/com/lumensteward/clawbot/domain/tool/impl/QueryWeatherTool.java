package com.lumensteward.clawbot.domain.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lumensteward.clawbot.common.util.JsonUtils;
import com.lumensteward.clawbot.domain.tool.JsonSchema;
import com.lumensteward.clawbot.domain.tool.Tool;
import com.lumensteward.clawbot.domain.tool.ToolContext;
import com.lumensteward.clawbot.domain.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * 天气查询工具（SRS FR-23 验收① 的<b>示例插件</b>，name={@value #NAME}）。
 *
 * <p><b>本类存在的目的</b>是持续证明「新增工具零改对话引擎」：除本类外，编排器、Prompt 文件、
 * 安全一致性校验、监控看板、任务会话<b>均未改动</b>，新工具即可被模型发现、调用、审计与归因。
 * 三处派生口径由本类自述（{@link #claimKeywords()} / {@link #monitorDomain()} /
 * {@link #taskIntent()}），注册表按名反查——这是零改动得以成立的前提（W3）。
 *
 * <p><b>数据来源刻意保持自包含</b>：T03 无外网（AC-D1），本工具只回一份内置样例城市数据集，
 * 且在结果里带 {@code data_source=mock-demo} 标注；未收录城市返回 {@code EMPTY_RESULT} 而非编造
 * （BR-09）。接入真实天气服务时只需把数据源换成领域端口 + 基础设施适配器，本类与引擎仍零改动。
 *
 * <p><b>默认不放量：</b>由 {@code orchestration.disabled-tools} 配置项禁用（V1.0.14 起），
 * 即「1 个类 + 1 条配置」中的那条配置；打开后无需重启即对模型可见（FR-18 工具开关）。
 */
@Component
public class QueryWeatherTool implements Tool {

    /** 工具名（全局唯一，FR-23）。 */
    public static final String NAME = "query_weather";

    private static final Logger log = LoggerFactory.getLogger(QueryWeatherTool.class);

    private static final String DESCRIPTION =
            "查询城市天气（演示插件：仅覆盖内置样例城市，返回带 mock-demo 标注的样例数据，"
                    + "未收录城市会明确说明查不到）。适用于用户询问'明天天气怎么样'等场景。";

    private static final String PARAMETERS_SCHEMA = """
            {"type":"object","properties":{
              "city":{"type":"string","description":"城市名，如「杭州」","maxLength":32},
              "date":{"type":"string","description":"日期，格式 YYYY-MM-DD，可空（默认今天）"}
            },"required":["city"]}
            """;

    /** 样例城市 → 天气概况（无外网兜底，刻意不含真实预报能力，BR-09）。 */
    private static final Map<String, String> DEMO_CITIES = Map.of(
            "杭州", "晴,26,18~26,东南风 2 级",
            "北京", "多云,21,14~21,北风 3 级",
            "上海", "小雨,24,20~24,东风 4 级");

    /** 支持的样例城市提示文案（顺序固定，便于复现）。 */
    private static final String DEMO_CITY_HINT = "杭州、北京、上海";

    /** 数据来源标注（回注模型时可见，避免被当作真实预报播报）。 */
    private static final String DATA_SOURCE = "mock-demo";

    private final JsonSchema schema;

    /** 无外部依赖构造器（示例插件刻意自包含，FR-23 零改装配）。 */
    public QueryWeatherTool() {
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
    public boolean readOnly() {
        return true;
    }

    @Override
    public Set<String> claimKeywords() {
        return Set.of("查询", "天气", "气温", "预报");
    }

    @Override
    public String monitorDomain() {
        return "weather";
    }

    @Override
    public ToolResult execute(ToolContext context, JsonNode args) {
        long start = System.currentTimeMillis();
        String city = text(args, "city");
        if (city == null || city.isBlank()) {
            return ToolResult.failure("INVALID_ARGS", "缺少城市名 city", false);
        }
        String normalized = city.trim();
        String demo = DEMO_CITIES.get(normalized);
        log.info("天气查询（示例插件） city={} hit={}", normalized, demo != null);
        if (demo == null) {
            return ToolResult.failure("EMPTY_RESULT",
                    "演示数据集未收录「" + normalized + "」，当前仅支持：" + DEMO_CITY_HINT, false);
        }
        String[] parts = demo.split(",");
        String date = text(args, "date");
        ObjectNode data = JsonUtils.mapper().createObjectNode();
        data.put("city", normalized);
        data.put("date", date == null || date.isBlank() ? "今天" : date.trim());
        data.put("condition", parts[0]);
        data.put("temp_c", Integer.parseInt(parts[1]));
        data.put("temp_range", parts[2]);
        data.put("wind", parts[3]);
        data.put("data_source", DATA_SOURCE);
        return ToolResult.success(data, elapsed(start));
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
