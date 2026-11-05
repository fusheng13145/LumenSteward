package com.lumensteward.clawbot.domain.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lumensteward.clawbot.common.util.JsonUtils;
import com.lumensteward.clawbot.domain.port.LinkFetchException;
import com.lumensteward.clawbot.domain.port.LinkFetchPort;
import com.lumensteward.clawbot.domain.port.model.LinkContent;
import com.lumensteward.clawbot.domain.tool.JsonSchema;
import com.lumensteward.clawbot.domain.tool.Tool;
import com.lumensteward.clawbot.domain.tool.ToolContext;
import com.lumensteward.clawbot.domain.tool.ToolResult;
import com.lumensteward.clawbot.domain.tool.ToolVisibilityContext;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 链接读取工具（迭代 4 W16，name=read_link）：抓取用户分享的网页并提取正文纯文本，
 * 总结由模型完成——工具只交事实（标题 + 正文 + 是否截断），不代笔观点。
 *
 * <p><b>出现条件（W10 同轮情境门控）：</b>仅当本轮用户消息含链接时进入函数 Schema
 * （{@code ToolVisibilityContext.linkPresent}，link 消息与文本内嵌 URL 均命中）；
 * 与 {@code ask_image} <b>同轮互斥</b>（本轮含链接时 read_link 优先），使 D2 的 P1
 * 「链接摘要」与 P2「每轮下发 Schema ≤8」同时成立。
 *
 * <p>{@code idempotent=true}（抓取是纯读）、{@code readOnly=true}（回放干跑可重试）、
 * {@code critical=false}（读不了就如实回告，不中断）。抓取失败转结构化失败，
 * 正文绝不编造（BR-09）。
 */
@Component
public class ReadLinkTool implements Tool {

    /** 工具名（全局唯一，FR-23）。 */
    public static final String NAME = "read_link";

    private static final String DESCRIPTION =
            "读取用户分享的网页链接并提取正文要点素材。用户发来链接希望你总结、解读时用它。"
                    + "仅在消息包含链接时可用。";

    private static final String PARAMETERS_SCHEMA = """
            {"type":"object","properties":{
              "url":{"type":"string","description":"要读取的网页地址（http/https，取自用户消息中的链接）"}
            },"required":["url"]}
            """;

    private final LinkFetchPort linkFetchPort;
    private final JsonSchema schema;

    /**
     * 构造器注入（G-14）。
     *
     * @param linkFetchPort 链接抓取端口（为 null 时本工具恒不可见，standalone 构造兜底）
     */
    public ReadLinkTool(LinkFetchPort linkFetchPort) {
        this.linkFetchPort = linkFetchPort;
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
        // 与链接语义同族；「总结/解读」不在完成态动词词表内，不构成执行性声明面
        return Set.of("链接", "网页", "网址");
    }

    @Override
    public String monitorDomain() {
        return "link";
    }

    @Override
    public boolean visibleIn(ToolVisibilityContext context) {
        return linkFetchPort != null && context != null && context.linkPresent();
    }

    @Override
    public ToolResult execute(ToolContext context, JsonNode args) {
        long start = System.currentTimeMillis();
        String url = text(args, "url");
        if (url == null || url.isBlank()) {
            return ToolResult.failure("INVALID_ARGS", "缺少要读取的链接 url", false);
        }
        try {
            LinkContent content = linkFetchPort.fetch(url.trim());
            ObjectNode data = JsonUtils.mapper().createObjectNode();
            data.put("url", url.trim());
            data.put("title", content.title());
            data.put("content", content.text());
            data.put("truncated", content.truncated());
            return ToolResult.success(data, elapsed(start))
                    .withMessage("网页正文在 data.content（标题：" + (content.title().isBlank()
                            ? "（无标题）" : content.title()) + "），"
                            + "请基于它总结要点；正文被截断时如实说明你只读到了开头部分。");
        } catch (LinkFetchException e) {
            return ToolResult.failure("LINK_FETCH_FAILED",
                    "这个链接暂时读不了：" + e.getMessage() + "。请如实告知用户，不要猜测内容。", true);
        }
    }

    private static String text(JsonNode args, String field) {
        if (args == null || !args.hasNonNull(field)) {
            return null;
        }
        return args.get(field).asText();
    }

    private static long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }
}
