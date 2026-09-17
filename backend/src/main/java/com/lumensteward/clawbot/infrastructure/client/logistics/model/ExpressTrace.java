package com.lumensteward.clawbot.infrastructure.client.logistics.model;

import java.util.List;

/**
 * 快递轨迹（SRS FR-12，模型定义）。
 *
 * <p>仅作为外部物流 SPI 的返回模型；<b>不</b>实现 {@code Tool} 接口，故不会被下发给模型
 * （BR-04：不得让模型编造可调用能力）。
 *
 * @param companyCode 快递公司编码
 * @param trackingNo  运单号（日志须脱敏，BR-15）
 * @param state       状态：已签收/派送中/运输中/无数据
 * @param found       是否查询到
 * @param nodes       轨迹节点
 */
public record ExpressTrace(String companyCode, String trackingNo, String state,
                           boolean found, List<Node> nodes) {

    public ExpressTrace {
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
    }

    /**
     * 轨迹节点。
     *
     * @param time    时间（文本，如 2025-01-01 10:00）
     * @param status  状态描述
     * @param context 上下文描述
     */
    public record Node(String time, String status, String context) {
    }
}
