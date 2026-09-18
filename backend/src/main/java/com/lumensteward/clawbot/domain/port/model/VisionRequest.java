package com.lumensteward.clawbot.domain.port.model;

/**
 * 视觉（多模态）请求（领域端口 DTO，上提自 {@code infrastructure/client/llm/dto}）。
 *
 * @param model           视觉模型名（可空，由实现方决定）
 * @param imageUrlOrBase64 图片 URL 或 Base64
 * @param question        聚焦问题（FR-10：pet/object/ocr 场景）
 */
public record VisionRequest(String model, String imageUrlOrBase64, String question) {
}
