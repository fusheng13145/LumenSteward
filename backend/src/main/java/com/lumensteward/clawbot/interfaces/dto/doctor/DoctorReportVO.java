package com.lumensteward.clawbot.interfaces.dto.doctor;

import java.time.Instant;
import java.util.List;

/**
 * 结构化体检报告视图（架构 4.3 / SUP-05 / A-1）。
 *
 * <p>与 {@code infrastructure.bootstrap.doctor.DoctorReport} 同源，作为对外 DTO 隔离内部模型。
 *
 * @param overall   整体结论：UP / DEGRADED / DOWN
 * @param checkedAt 体检时间
 * @param items     各依赖项明细
 */
public record DoctorReportVO(String overall,
                             Instant checkedAt,
                             List<Item> items) {

    /**
     * 单项依赖体检结果。
     *
     * @param name             依赖名
     * @param connectivity     连通性：UP / DOWN / TIMEOUT / N/A
     * @param mode             运行模式：mock / real / 未配置
     * @param configConclusion 配置校验结论
     * @param detail           补充说明（不含密钥明文）
     */
    public record Item(String name,
                       String connectivity,
                       String mode,
                       String configConclusion,
                       String detail) {
    }
}
