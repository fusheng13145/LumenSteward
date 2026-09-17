package com.lumensteward.clawbot.infrastructure.bootstrap;

import com.lumensteward.clawbot.infrastructure.bootstrap.doctor.DoctorReport;

/**
 * 启动自检与连通性体检（SUP-05 / A-1 / 架构 5.4）。
 *
 * <p>逐项校验 DB / Redis / 微信通道 / LLM / TTS / 物流 / 地图 的连通性、运行模式与配置校验结论，
 * 输出结构化 {@link DoctorReport}。实现须满足：单项探测超时 ≤2s，且<b>任何情况下都不抛出异常</b>
 * （体检失败以报告形式呈现，而非 5xx，AC-D4）。
 */
public interface StartupDoctor {

    /**
     * 执行一次体检。
     *
     * @return 结构化体检报告（永不返回 null，永不抛出异常）
     */
    DoctorReport diagnose();
}
