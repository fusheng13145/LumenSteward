package com.lumensteward.clawbot.infrastructure.client.llm.dto;

/**
 * 视觉（多模态）请求（架构 5.1，MVP 仅契约，Mock 实现）。
 *
 * @param model           视觉模型名
 * @param imageUrlOrBase64 图片 URL 或 Base64
 * @param question        聚焦问题（FR-10）
 */
public record VisionRequest(String model, String imageUrlOrBase64, String question) {
}
