package com.lumensteward.clawbot.application.config;

/**
 * 运行时动态配置键常量（FR-18 / 迭代 2 T10）。
 *
 * <p>键名与 {@code sys_config.config_key}（迁移脚本 {@code V1.0.3__seed_sys_config.sql}）一一对应，
 * 命名规范为「模块.子项」（7.6）。动态读取一律经 {@link DynamicConfigService}，<b>不存在即回退启动期静态值</b>，
 * 绝不因配置缺失导致链路失败。
 */
public final class ConfigKeys {

    /** LLM 提供方：mock / openai-compatible（B-2 运行时路由）。 */
    public static final String LLM_PROVIDER = "llm.provider";

    /** 对话模型名（B-2：切换免重启）。 */
    public static final String LLM_MODEL = "llm.model";

    /** real 模式服务地址。 */
    public static final String LLM_BASE_URL = "llm.base-url";

    /** 对话调用超时（秒）。 */
    public static final String LLM_TIMEOUT_SECONDS = "llm.timeout-seconds";

    /** 上下文输入预算 token（FR-04）。 */
    public static final String LLM_INPUT_BUDGET_TOKENS = "llm.input-budget-tokens";

    /** 输出预留 token（FR-04）。 */
    public static final String LLM_RESERVED_OUTPUT_TOKENS = "llm.reserved-output-tokens";

    /** Agent Loop 最大轮次（SC-01）。 */
    public static final String ORCHESTRATION_MAX_ROUNDS = "orchestration.max-rounds";

    /** 单轮最大并行工具数（SC-02）。 */
    public static final String ORCHESTRATION_MAX_PARALLEL_TOOLS = "orchestration.max-parallel-tools";

    /** 链路总时间预算 ms（SC-03）。 */
    public static final String ORCHESTRATION_TOTAL_BUDGET_MS = "orchestration.total-budget-ms";

    /** 单工具执行超时 ms（SC-03）。 */
    public static final String ORCHESTRATION_TOOL_TIMEOUT_MS = "orchestration.tool-timeout-ms";

    /** 被禁用工具名数组（FR-18 工具开关，JSON 数组）。 */
    public static final String ORCHESTRATION_DISABLED_TOOLS = "orchestration.disabled-tools";

    /** 内容安全不可用时是否 Fail-Closed（BR-12）。 */
    public static final String SAFETY_FAIL_CLOSED = "safety.fail-closed";

    /** 严格模式（9.4.5）。 */
    public static final String SAFETY_STRICT_MODE = "safety.strict-mode";

    /** 超时兜底文案（9.5）。 */
    public static final String FALLBACK_TIMEOUT_TEXT = "fallback.timeout-text";

    /** 执行一致性校验兜底文案（BR-04）。 */
    public static final String FALLBACK_HALLUCINATION_TEXT = "fallback.hallucination-text";

    /** 内容安全拦截兜底文案（BR-12）。 */
    public static final String FALLBACK_BLOCKED_TEXT = "fallback.blocked-text";

    /** 微信 Mock 通道开关。 */
    public static final String WX_MOCK_ENABLED = "wx.mock.enabled";

    private ConfigKeys() {
    }
}
