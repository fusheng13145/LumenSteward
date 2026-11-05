package com.lumensteward.clawbot.domain.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lumensteward.clawbot.common.util.JsonUtils;
import com.lumensteward.clawbot.domain.port.LinkFetchException;
import com.lumensteward.clawbot.domain.port.LinkFetchPort;
import com.lumensteward.clawbot.domain.port.model.LinkContent;
import com.lumensteward.clawbot.domain.tool.impl.ReadLinkTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 链接读取工具测试（迭代 4 W16）：情境门控可见性、抓取成功/失败路径、自述元数据。
 */
class ReadLinkToolTest {

    private static final ToolContext CTX = ToolContext.of("trace-link", "openid-link", 1L, 0);

    @Test
    @DisplayName("可见性：仅本轮含链接（linkPresent）时可见；端口为 null 恒不可见")
    void shouldBeVisibleOnlyWhenLinkPresent() {
        ReadLinkTool tool = new ReadLinkTool(url -> new LinkContent("t", "正文", false));

        assertThat(tool.visibleIn(new ToolVisibilityContext("openid", false, null, true))).isTrue();
        assertThat(tool.visibleIn(new ToolVisibilityContext("openid", false, null, false))).isFalse();
        assertThat(tool.visibleIn(ToolVisibilityContext.of("openid"))).isFalse();
        assertThat(new ReadLinkTool(null).visibleIn(
                new ToolVisibilityContext("openid", false, null, true))).isFalse();
    }

    @Test
    @DisplayName("自述元数据：判定词收链接语义族、归因域 link、只读可幂等")
    void shouldSelfDescribeMetadata() {
        ReadLinkTool tool = new ReadLinkTool(url -> null);

        assertThat(tool.name()).isEqualTo("read_link");
        assertThat(tool.claimKeywords()).containsExactlyInAnyOrder("链接", "网页", "网址");
        assertThat(tool.monitorDomain()).isEqualTo("link");
        assertThat(tool.readOnly()).isTrue();
        assertThat(tool.idempotent()).isTrue();
        assertThat(tool.critical()).isFalse();
    }

    @Test
    @DisplayName("缺 url → INVALID_ARGS")
    void shouldRejectMissingUrl() {
        ReadLinkTool tool = new ReadLinkTool(url -> new LinkContent("t", "正文", false));

        ToolResult result = tool.execute(CTX, args(null));

        assertThat(result.errorType()).isEqualTo("INVALID_ARGS");
    }

    @Test
    @DisplayName("抓取成功：标题/正文/截断标记随结果返回，message 引导模型总结")
    void shouldReturnContentOnSuccess() {
        ReadLinkTool tool = new ReadLinkTool(url -> {
            assertThat(url).isEqualTo("https://example.com/hello");
            return new LinkContent("示例页", "这里是正文。", true);
        });

        ToolResult result = tool.execute(CTX, args("https://example.com/hello"));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.data().path("title").asText()).isEqualTo("示例页");
        assertThat(result.data().path("content").asText()).isEqualTo("这里是正文。");
        assertThat(result.data().path("truncated").asBoolean()).isTrue();
        assertThat(result.message()).contains("总结").contains("截断");
    }

    @Test
    @DisplayName("抓取失败 → LINK_FETCH_FAILED 如实回告，不编造正文（BR-09）")
    void shouldFailHonestlyWhenFetchFails() {
        ReadLinkTool tool = new ReadLinkTool(url -> {
            throw new LinkFetchException("目标返回 HTTP 500");
        });

        ToolResult result = tool.execute(CTX, args("https://example.com/broken"));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.errorType()).isEqualTo("LINK_FETCH_FAILED");
        assertThat(result.message()).contains("读不了").contains("不要猜测内容");
    }

    private static JsonNode args(String url) {
        ObjectNode node = JsonUtils.mapper().createObjectNode();
        if (url != null) {
            node.put("url", url);
        }
        return node;
    }
}
