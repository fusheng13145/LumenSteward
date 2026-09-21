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

    /** 限流白名单（逗号分隔 openid，豁免限流；FR-20 备选流 2a）。 */
    public static final String RATE_LIMIT_WHITELIST = "rate_limit.whitelist";

    /** 成本保护日 token 预算（FR-20 成本保护，默认 200000）。 */
    public static final String RATE_LIMIT_DAILY_TOKEN_BUDGET = "rate_limit.daily_token_budget";

    /** 单条消息最大字符数（FR-20 ④，默认 2000）。 */
    public static final String RATE_LIMIT_MAX_MESSAGE_LENGTH = "rate_limit.max_message_length";

    /** 个人状态库自动生长开关（W6，默认 false：每条消息额外调用模型，有成本）。 */
    public static final String MEMORY_GROWTH_ENABLED = "memory.growth.enabled";

    /** 单次对话最多落库的事实条数（W6，防噪声写入）。 */
    public static final String MEMORY_GROWTH_MAX_ITEMS = "memory.growth.max-items";

    /** 个人状态库召回注入开关（W6，默认 true）。 */
    public static final String MEMORY_RECALL_ENABLED = "memory.recall.enabled";

    /** 召回注入最大条数（W6，防上下文挤占）。 */
    public static final String MEMORY_RECALL_MAX_ITEMS = "memory.recall.max-items";

    /** 召回注入最大字符数（W6，防上下文挤占）。 */
    public static final String MEMORY_RECALL_MAX_CHARS = "memory.recall.max-chars";

    // ===== 灰度开关与熔断回滚（FR-22 / 迭代 4 W2）=====

    /** 状态库生长的灰度比例（0~100；100=全量，0=灰度关闭含白名单）。 */
    public static final String GRAY_MEMORY_GROWTH_PERCENT = "gray.memory_growth.percent";

    /** 状态库生长的灰度白名单（逗号分隔 openid，优先于比例命中）。 */
    public static final String GRAY_MEMORY_GROWTH_WHITELIST = "gray.memory_growth.whitelist";

    /** 灰度熔断开关（默认开；关闭后不再自动回滚，仅保留手动回滚）。 */
    public static final String GRAY_BREAKER_ENABLED = "gray.breaker.enabled";

    /** 熔断观测窗口（分钟）。 */
    public static final String GRAY_BREAKER_WINDOW_MINUTES = "gray.breaker.window-minutes";

    /** 熔断最小样本数（窗口内链路轮次不足则不回滚，避免冷启动误熔断）。 */
    public static final String GRAY_BREAKER_MIN_SAMPLES = "gray.breaker.min-samples";

    /** 熔断错误率阈值（百分比 0~100，窗口内 L2~L4 异常数 / 链路轮次）。 */
    public static final String GRAY_BREAKER_ERROR_RATE_PERCENT = "gray.breaker.error-rate-percent";

    /** 熔断 P95 时延阈值（ms，0 表示不启用时延判据）。 */
    public static final String GRAY_BREAKER_P95_MS = "gray.breaker.p95-ms";

    private ConfigKeys() {
    }
}
