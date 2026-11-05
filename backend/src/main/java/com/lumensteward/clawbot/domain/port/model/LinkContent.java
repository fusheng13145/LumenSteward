package com.lumensteward.clawbot.domain.port.model;

/**
 * 链接抓取结果（迭代 4 W16）：已剥离标记的纯文本正文，供模型总结。
 *
 * @param title     页面标题（取自 {@code <title>}；无则空串）
 * @param text      正文纯文本（已去脚本/样式/标记与实体解码，超长截断）
 * @param truncated 正文是否因长度上限被截断
 */
public record LinkContent(String title, String text, boolean truncated) {
}
