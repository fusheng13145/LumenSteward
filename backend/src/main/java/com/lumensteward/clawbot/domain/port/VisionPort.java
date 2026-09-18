package com.lumensteward.clawbot.domain.port;

import com.lumensteward.clawbot.domain.port.model.VisionRequest;
import com.lumensteward.clawbot.domain.port.model.VisionResult;

/**
 * 视觉（多模态）能力端口（SRS FR-10 / FR-06）。
 *
 * <p>上提自 {@code infrastructure/client/llm}，使 {@code domain} 不再依赖基础设施
 * （依赖铁律，NFR-MA-03）。由 {@code LlmVisionAdapter} 委托 {@code LlmClient.vision} 实现。
 */
public interface VisionPort {

    /**
     * 视觉识别。
     *
     * @param request 视觉请求（图片 + 聚焦问题）
     * @return 视觉结果
     * @throws VisionPortException 视觉能力不可用或协议异常
     */
    VisionResult vision(VisionRequest request) throws VisionPortException;
}
