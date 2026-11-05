package com.lumensteward.clawbot.infrastructure.fetch;

import org.springframework.web.util.HtmlUtils;

import java.util.regex.Pattern;

/**
 * HTML → 纯文本提取器（迭代 4 W16，零第三方依赖）。
 *
 * <p>刻意不引入 jsoup/HtmlUnit（离线构建与最小依赖面约束）：按标签块剥离
 * （script/style/noscript/注释优先，再剥全部标签）+ Spring 自带
 * {@link HtmlUtils#htmlUnescape(String)} 解码实体 + 空白收紧。结果是「够模型总结」的
 * 启发式正文，不是排版级还原——广告残留等噪声由模型侧消化，属已知边界（手册 §7.20）。
 */
public final class HtmlTextExtractor {

    /** 正文输出上限（字符）：超出截断，模型总结不需要整页。 */
    public static final int MAX_TEXT_LENGTH = 8000;

    private static final Pattern SCRIPT_BLOCK = Pattern.compile(
            "(?is)<(script|style|noscript|svg|head)\\b[^>]*>.*?</\\1>");
    private static final Pattern COMMENTS = Pattern.compile("(?s)<!--.*?-->");
    private static final Pattern TAGS = Pattern.compile("(?s)<[^>]*>");
    private static final Pattern BLOCK_END = Pattern.compile("(?i)</(p|div|br|li|tr|h[1-6]|section|article)>");

    private HtmlTextExtractor() {
        // 纯函数工具，禁止实例化
    }

    /**
     * 提取标题（{@code <title>} 文本；无则空串）。
     *
     * @param html 页面 HTML
     * @return 标题（已解码，去首尾空白）
     */
    public static String extractTitle(String html) {
        if (html == null) {
            return "";
        }
        var matcher = Pattern.compile("(?is)<title[^>]*>(.*?)</title>").matcher(html);
        if (!matcher.find()) {
            return "";
        }
        return HtmlUtils.htmlUnescape(matcher.group(1)).trim();
    }

    /**
     * 提取正文纯文本（超长截断）。
     *
     * @param html 页面 HTML
     * @return 正文文本与是否截断
     */
    public static Extracted extract(String html) {
        if (html == null || html.isBlank()) {
            return new Extracted("", false);
        }
        String cleaned = TAGS.matcher(
                COMMENTS.matcher(
                        BLOCK_END.matcher(
                                SCRIPT_BLOCK.matcher(html).replaceAll("\n")).replaceAll("\n"))
                        .replaceAll("")).replaceAll(" ");
        String decoded = HtmlUtils.htmlUnescape(cleaned);
        String collapsed = decoded.replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll(" ?\\n ?", "\n")
                .replaceAll("\\n{2,}", "\n")
                .trim();
        if (collapsed.length() <= MAX_TEXT_LENGTH) {
            return new Extracted(collapsed, false);
        }
        return new Extracted(collapsed.substring(0, MAX_TEXT_LENGTH), true);
    }

    /** 正文提取结果。 */
    public record Extracted(String text, boolean truncated) {
    }
}
