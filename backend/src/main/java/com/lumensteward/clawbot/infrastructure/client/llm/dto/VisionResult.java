package com.lumensteward.clawbot.infrastructure.client.llm.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 视觉结果（架构 5.1）。
 *
 * @param description 识别描述（BR-09：识别结果不可臆造）
 * @param confidence  置信度（[0,1]）
 * @param raw         原始响应
 */
public record VisionResult(String description, double confidence, JsonNode raw) {
}
