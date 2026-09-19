package com.lumensteward.clawbot.domain.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lumensteward.clawbot.common.util.JsonUtils;
import com.lumensteward.clawbot.common.util.MaskUtils;
import com.lumensteward.clawbot.domain.port.ExpressQueryException;
import com.lumensteward.clawbot.domain.port.ExpressQueryPort;
import com.lumensteward.clawbot.domain.port.model.ExpressTrace;
import com.lumensteward.clawbot.domain.tool.JsonSchema;
import com.lumensteward.clawbot.domain.tool.Tool;
import com.lumensteward.clawbot.domain.tool.ToolContext;
import com.lumensteward.clawbot.domain.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 快递查询工具（架构 5.1 / SRS FR-12，name=query_express）。
 *
 * <p>{@code idempotent=true}、{@code critical=false}。运单号前缀可推断承运商（SF=顺丰 / ZTO=中通 /
 * YT=圆通 / YD=韵达 / EMS=邮政 / JD=京东），覆盖 6 家（PRD FR-12 验收①）；未查到返回
 * {@code EMPTY_RESULT} 并提示核对单号，<b>不编造</b>（BR-09）；运单号在日志经 {@link MaskUtils} 脱敏（BR-15）。
 */
@Component
public class QueryExpressTool implements Tool {

    /** 工具名（全局唯一，FR-23）。 */
    public static final String NAME = "query_express";

    private static final Logger log = LoggerFactory.getLogger(QueryExpressTool.class);

    private static final String DESCRIPTION =
            "查询快递物流轨迹。适用于用户发送运单号或询问'我的快递到哪了'等场景。需提供 tracking_no。";

    private static final String PARAMETERS_SCHEMA = """
            {"type":"object","properties":{
              "tracking_no":{"type":"string","description":"运单号"},
              "company_code":{"type":"string","description":"快递公司编码，可空（可由运单号前缀推断）"}
            },"required":["tracking_no"]}
            """;

    /** 运单号前缀 → 公司编码（6 家）。 */
    private static final Map<String, String> PREFIX_TO_COMPANY = Map.ofEntries(
            Map.entry("SF", "SF"),
            Map.entry("ZTO", "ZTO"),
            Map.entry("YT", "YTO"),
            Map.entry("YD", "YD"),
            Map.entry("EMS", "EMS"),
            Map.entry("JD", "JD"));

    private final ExpressQueryPort expressQueryPort;
    private final JsonSchema schema;

    /**
     * 构造器注入（G-14）。
     *
     * @param expressQueryPort 快递查询端口
     */
    public QueryExpressTool(ExpressQueryPort expressQueryPort) {
        this.expressQueryPort = expressQueryPort;
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
    public ToolResult execute(ToolContext context, JsonNode args) {
        long start = System.currentTimeMillis();
        String trackingNo = text(args, "tracking_no");
        if (trackingNo == null || trackingNo.isBlank()) {
            return ToolResult.failure("INVALID_ARGS", "缺少运单号 tracking_no", false);
        }
        String companyCode = text(args, "company_code");
        if (companyCode == null || companyCode.isBlank()) {
            companyCode = inferCompanyCode(trackingNo);
        }
        try {
            ExpressTrace trace = expressQueryPort.query(companyCode, trackingNo);
            log.info("快递查询 company={} trackingNo={} found={}",
                    companyCode, MaskUtils.trackingNo(trackingNo), trace != null && trace.found());
            if (trace == null || !trace.found()) {
                // 未查到：提示核对单号，不编造（BR-09）
                return ToolResult.failure("EMPTY_RESULT", "未在物流系统查到该单号，请核对运单号是否正确", false);
            }
            ObjectNode data = JsonUtils.mapper().createObjectNode();
            data.put("company", companyCode);
            data.put("tracking_no", MaskUtils.trackingNo(trackingNo));
            data.put("state", trace.state());
            ArrayNode nodes = data.putArray("nodes");
            if (trace.nodes() != null) {
                for (ExpressTrace.Node node : trace.nodes()) {
                    ObjectNode n = nodes.addObject();
                    n.put("time", node.time());
                    n.put("status", node.status());
                    n.put("context", node.context());
                }
            }
            return ToolResult.success(data, elapsed(start));
        } catch (ExpressQueryException e) {
            log.warn("快递查询异常: {}", e.getMessage());
            return ToolResult.failure("EXPRESS_FAILED", "物流查询失败，请稍后重试", false);
        }
    }

    /**
     * 由运单号前缀推断承运商编码（覆盖 6 家）。
     *
     * @param trackingNo 运单号
     * @return 公司编码；无法推断时返回 {@code UNKNOWN}
     */
    private static String inferCompanyCode(String trackingNo) {
        String upper = trackingNo.toUpperCase();
        for (Map.Entry<String, String> entry : PREFIX_TO_COMPANY.entrySet()) {
            if (upper.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return "UNKNOWN";
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
