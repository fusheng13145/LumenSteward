package com.lumensteward.clawbot.infrastructure.fetch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTML → 纯文本提取器测试（迭代 4 W16）：标题、块剥离、实体解码、截断。
 */
class HtmlTextExtractorTest {

    @Test
    @DisplayName("标题提取：取 <title> 文本并解码；无标题返回空串")
    void shouldExtractTitle() {
        assertThat(HtmlTextExtractor.extractTitle("<html><head><title>渐近线 &amp; 附录</title></head></html>"))
                .isEqualTo("渐近线 & 附录");
        assertThat(HtmlTextExtractor.extractTitle("<html><body>没有标题</body></html>")).isEmpty();
        assertThat(HtmlTextExtractor.extractTitle(null)).isEmpty();
    }

    @Test
    @DisplayName("正文提取：script/style/head 块整体剥离，标签转空白，实体解码，空白收紧")
    void shouldStripBlocksAndTags() {
        String html = """
                <html><head><style>body{color:red}</style></head>
                <body>
                <script>alert('x')</script>
                <h1>第一段标题</h1>
                <p>正文一 &quot;引号&quot; &lt;已解码&gt;。</p>
                <div>正文二<br>换行内容</div>
                <!-- 注释不出现 -->
                </body></html>
                """;
        HtmlTextExtractor.Extracted extracted = HtmlTextExtractor.extract(html);

        assertThat(extracted.text()).doesNotContain("alert").doesNotContain("color:red");
        assertThat(extracted.text()).doesNotContain("注释不出现").doesNotContain("<p>");
        assertThat(extracted.text()).contains("第一段标题").contains("正文一 \"引号\" <已解码>。");
        assertThat(extracted.text()).contains("正文二").contains("换行内容");
        assertThat(extracted.truncated()).isFalse();
    }

    @Test
    @DisplayName("超长正文按 8000 字符截断并置 truncated 标记")
    void shouldTruncateLongText() {
        String html = "<p>" + "字".repeat(9000) + "</p>";
        HtmlTextExtractor.Extracted extracted = HtmlTextExtractor.extract(html);

        assertThat(extracted.text()).hasSize(HtmlTextExtractor.MAX_TEXT_LENGTH);
        assertThat(extracted.truncated()).isTrue();
    }

    @Test
    @DisplayName("空输入返回空文本不截断")
    void shouldHandleBlankInput() {
        assertThat(HtmlTextExtractor.extract(null).text()).isEmpty();
        assertThat(HtmlTextExtractor.extract("  ").truncated()).isFalse();
    }
}
