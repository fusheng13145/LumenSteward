package com.lumensteward.clawbot.application.fallback;

import java.util.Map;

/**
 * 兜底文案渲染（架构 5.2 / SRS 9.5）。
 *
 * <p>降级的目标不是"让系统看起来还在工作"，而是"在能力收窄时仍然诚实"（BR-04）。所有降级路径的
 * 最终输出均为可理解、不误导的自然语言。
 */
public interface FallbackService {

    /**
     * 渲染兜底文案。
     *
     * @param reason 降级原因
     * @param ctx    渲染上下文（如 petName），可为 null
     * @return 面向用户的自然语言文案
     */
    String render(FallbackReason reason, Map<String, Object> ctx);
}
