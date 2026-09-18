package com.lumensteward.clawbot.infrastructure.client;

import com.lumensteward.clawbot.domain.port.VisionPort;
import com.lumensteward.clawbot.domain.port.VisionPortException;
import com.lumensteward.clawbot.domain.port.model.VisionRequest;
import com.lumensteward.clawbot.domain.port.model.VisionResult;
import com.lumensteward.clawbot.infrastructure.client.llm.LlmClient;
import com.lumensteward.clawbot.infrastructure.client.llm.exception.LlmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 视觉能力适配器（SRS FR-10 / FR-06）。
 *
 * <p>实现领域端口 {@link VisionPort}，委托 {@link LlmClient#vision} 完成多模态识别；将基础设施异常
 * {@link LlmException} 转译为领域异常 {@link VisionPortException}，使领域层不反向依赖基础设施。
 */
@Component
public class LlmVisionAdapter implements VisionPort {

    private static final Logger log = LoggerFactory.getLogger(LlmVisionAdapter.class);

    private final LlmClient llmClient;

    /**
     * 构造器注入（G-14）。
     *
     * @param llmClient LLM 客户端（Mock / OpenAI 兼容）
     */
    public LlmVisionAdapter(LlmClient llmClient) {
        this.llmClient = llmClient;
    }

    @Override
    public VisionResult vision(VisionRequest request) throws VisionPortException {
        if (request == null
                || request.imageUrlOrBase64() == null
                || request.imageUrlOrBase64().isBlank()) {
            throw new VisionPortException("缺少图片内容（image_url 或 image_base64）");
        }
        try {
            // 端口与 LLM 网关已统一使用领域 DTO，直接透传
            return llmClient.vision(request);
        } catch (LlmException e) {
            log.warn("视觉调用失败: {}", e.getMessage());
            throw new VisionPortException("视觉能力暂不可用", e);
        }
    }
}
