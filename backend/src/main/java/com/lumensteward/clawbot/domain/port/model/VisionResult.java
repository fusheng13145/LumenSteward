package com.lumensteward.clawbot.domain.port.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 视觉结果（领域端口 DTO，上提自 {@code infrastructure/client/llm/dto}）。
 *
 * @param description 识别描述（BR-09：不可臆造，置信度不足须以"可能/疑似"措辞）
 * @param confidence  置信度（[0,1]）
 * @param raw         原始响应（可空）
 */
public record VisionResult(String description, double confidence, JsonNode raw) {
}
