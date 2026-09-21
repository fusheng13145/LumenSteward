package com.lumensteward.clawbot.domain.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.lumensteward.clawbot.domain.intent.IntentType;

import java.util.Set;

/**
 * 工具契约（FR-23 / 架构 5.1）。
 *
 * <p>工具以 Schema 驱动、插件化注册：新增工具零侵入（仅需实现本接口并声明为 Bean）。
 * 编排逻辑<b>不硬编码</b>具体工具名，仅依赖 {@link #idempotent()} / {@link #critical()} 等元信息
 * （符合 BR-05 / NFR-MA-01）。
 *
 * <p><b>FR-23「零改对话引擎」的边界（W3 落定）：</b>工具名一旦成为<b>封闭枚举</b>，插件化就是假的。
 * 因此周边三处派生口径——一致性校验关键词 {@link #claimKeywords()}、看板归因域
 * {@link #monitorDomain()}、任务会话意图 {@link #taskIntent()}——一律由工具<b>自述</b>、
 * 由消费方经 {@link ToolRegistry} 反查，不得在引擎侧写 {@code switch(toolName)}。
 * 三者皆有保守默认值，故只实现四个基本方法的旧工具依然合法（BR-32 向后兼容）。
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

    /**
     * 是否只读（执行无副作用）。
     *
     * <p>迭代 2 T12（A-2 回放调试台）：回放以「不制造新事实」为前提。只读工具可安全重放；
     * 非只读工具（写档案、向用户下发语音等）在干跑模式下<b>一律跳过并说明原因</b>，
     * 避免"调试动作"意外改写业务数据——这与 BR-04 同源：宁可少做，也不制造无法解释的副作用。
     *
     * <p>默认 {@code false}（保守）：新增工具若未声明，回放即跳过，需显式声明只读才允许干跑。
     *
     * @return true 表示执行无副作用
     */
    default boolean readOnly() {
        return false;
    }

    /**
     * 动作声明比对的语义关键词（FR-09 执行一致性校验用，FR-23 要求由工具<b>自述</b>）。
     *
     * <p>回复里出现这些词即视为「模型声称调用过本工具」，一致性校验器据此核对是否真有调用记录。
     * 默认<b>空集</b>是保守取向：新工具漏声明只会少一道校验，绝不至于把正常回复误判为幻觉。
     *
     * @return 关键词集合（不可为 null；无声明返回空集）
     */
    default Set<String> claimKeywords() {
        return Set.of();
    }

    /**
     * 监控归因域标签（FR-17 意图分布口径）。
     *
     * <p>默认 {@code chat}：未声明的工具不污染看板，其调用归入会话域而非凭空造一个域。
     *
     * @return 归因域标签（如 {@code express} / {@code navigation}）
     */
    default String monitorDomain() {
        return "chat";
    }

    /**
     * 本工具承载的意图（FR-24 任务会话的<b>话题切换判定</b>：追问回复落到其他意图即视为切换）。
     *
     * <p>默认 {@code null} = 不参与话题切换判定（保守：宁可继续等待槽位填充，也不因未知工具误判切换
     * 而放弃任务）。
     *
     * @return 意图类型；无对应意图返回 null
     */
    default IntentType taskIntent() {
        return null;
    }
}
