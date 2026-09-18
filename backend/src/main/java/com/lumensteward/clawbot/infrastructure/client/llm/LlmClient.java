package com.lumensteward.clawbot.infrastructure.client.llm;

import com.lumensteward.clawbot.domain.port.model.VisionRequest;
import com.lumensteward.clawbot.domain.port.model.VisionResult;
import com.lumensteward.clawbot.infrastructure.client.llm.dto.ChatRequest;
import com.lumensteward.clawbot.infrastructure.client.llm.dto.ChatResult;
import com.lumensteward.clawbot.infrastructure.client.llm.exception.LlmException;

/**
 * LLM 网关抽象（架构 5.1 / SRS 9.4.1，多供应商可替换）。
 *
 * <p>由 {@code llm.provider} 决定装配 {@link MockLlmClient} 还是
 * {@link OpenAiCompatibleLlmClient}，实现运行时切换零代码改动（AC-D3）。
 * 所有失败路径均以 {@link LlmException} 子类抛出，编排器据此走降级矩阵（SRS 9.5）。
 */
public interface LlmClient {

    /**
     * 文本与工具调用。
     *
     * @param request 请求
     * @return 结果
     * @throws LlmException 超时 / 不可用 / 协议非法
     */
    ChatResult chat(ChatRequest request) throws LlmException;

    /**
     * 多模态视觉（MVP 仅契约，Mock 实现）。
     *
     * @param request 请求
     * @return 结果
     * @throws LlmException 超时 / 不可用 / 协议非法
     */
    VisionResult vision(VisionRequest request) throws LlmException;

    /**
     * 供应商标识。
     *
     * @return {@code "mock"} 或 {@code "openai-compatible"}
     */
    String provider();
}
