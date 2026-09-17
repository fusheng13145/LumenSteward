package com.lumensteward.clawbot.infrastructure.bootstrap.doctor;

/**
 * 单项依赖体检结果（SUP-05 / 架构 5.4）。
 *
 * @param name             依赖名（如 MySQL / Redis / 微信通道 / LLM / TTS / 物流 / 地图）
 * @param connectivity     连通性：{@link #UP} / {@link #DOWN} / {@link #TIMEOUT} / {@link #NOT_CONFIGURED}
 * @param mode             运行模式：mock / real / 未配置
 * @param configConclusion 配置校验结论（就绪与否、缺失项）
 * @param detail           补充说明（不含密钥明文）
 */
public record DependencyCheck(String name,
                              String connectivity,
                              String mode,
                              String configConclusion,
                              String detail) {

    /** 连通（或 mock 模式自洽可用）。 */
    public static final String UP = "UP";
    /** 不可达。 */
    public static final String DOWN = "DOWN";
    /** 探测超时（≤2s 未返回）。 */
    public static final String TIMEOUT = "TIMEOUT";
    /** 未配置（可选依赖，不计入降级）。 */
    public static final String NOT_CONFIGURED = "N/A";
    /** 模式：未配置。 */
    public static final String MODE_NOT_CONFIGURED = "未配置";
}
