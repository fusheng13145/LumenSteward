package com.lumensteward.clawbot.infrastructure.bootstrap.doctor;

import java.time.Instant;
import java.util.List;

/**
 * 结构化体检报告（SUP-05 / A-1 / 架构 5.4）。
 *
 * <p>由 {@code StartupDoctor#diagnose()} 产出，经 {@code GET /api/doctor} 暴露。用于直接对抗
 * 前身「配置齐全但链路为空」的失败模式——把「能否真正连通」变成可读报告。
 *
 * @param overall   整体结论：{@link #OVERALL_UP} / {@link #OVERALL_DEGRADED} / {@link #OVERALL_DOWN}
 * @param checkedAt 体检时间
 * @param items     各依赖项明细（DB/Redis/微信/LLM/TTS/物流/地图）
 */
public record DoctorReport(String overall, Instant checkedAt, List<DependencyCheck> items) {

    /** 整体结论：全部体检项正常。 */
    public static final String OVERALL_UP = "UP";
    /** 整体结论：核心依赖正常，但存在降级项（可选依赖未配置或不可达）。 */
    public static final String OVERALL_DEGRADED = "DEGRADED";
    /** 整体结论：核心依赖（数据库）不可达。 */
    public static final String OVERALL_DOWN = "DOWN";
}
