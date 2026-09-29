package com.lumensteward.clawbot.domain.model;

import java.time.Instant;

/**
 * 最近一次识图的结构化结论（迭代 4 W11 识图缓存与追问续接）。
 *
 * <p>识图工具成功后按 openid 缓存，供后续追问（不重发图片）续接使用——这是 D1 场景 S2
 * 「第二问的模型入参里带上了第一问的识图结论」的数据载体。
 *
 * @param description  识别描述（与识图工具回注模型的口径一致，含低置信措辞）
 * @param scene        识别场景（pet / object / ocr）
 * @param confidence   置信度（0~1）
 * @param recognizedAt 识别完成时间
 */
public record RecentImage(String description, String scene, double confidence, Instant recognizedAt) {
}
