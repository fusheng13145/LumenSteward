package com.lumensteward.clawbot.domain.port.model;

import java.util.List;

/**
 * 快递轨迹（领域端口 DTO，上提自 {@code infrastructure/client/logistics/model}）。
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

    /** 轨迹节点。 */
    public record Node(String time, String status, String context) {
    }
}
