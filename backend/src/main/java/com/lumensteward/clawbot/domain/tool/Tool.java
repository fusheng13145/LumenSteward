package com.lumensteward.clawbot.domain.tool;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 工具契约（FR-23 / 架构 5.1）。
 *
 * <p>工具以 Schema 驱动、插件化注册：新增工具零侵入（仅需实现本接口并声明为 Bean）。
 * 编排逻辑<b>不硬编码</b>具体工具名，仅依赖 {@link #idempotent()} / {@link #critical()} 等元信息
 * （符合 BR-05 / NFR-MA-01）。
 */
public interface Tool {

    /** 全局唯一工具名（FR-23）。 */
    String name();

    /** 供模型理解的自然语言描述。 */
    String description();

    /** 参数 JSON Schema（启动时校验，FR-23 异常流 1a）。 */
    JsonSchema parametersSchema();

    /**
     * 执行工具。
     *
     * <p>BR-10：工具内部异常须转为结构化失败结果，<b>不得</b>直接抛出中断链路（除非属 SC-05 关键工具）。
     *
     * @param context 执行上下文（traceId/openid/sessionId/round）
     * @param args    已通过 Schema 校验的入参
     * @return 结构化结果
     */
    ToolResult execute(ToolContext context, JsonNode args);

    /** 是否幂等（决定能否自动重试）。 */
    boolean idempotent();

    /** 是否关键工具（SC-05：失败即中断依赖链）。 */
    boolean critical();
}
