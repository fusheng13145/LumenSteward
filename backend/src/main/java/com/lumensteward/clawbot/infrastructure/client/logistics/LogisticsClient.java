package com.lumensteward.clawbot.infrastructure.client.logistics;

import com.lumensteward.clawbot.infrastructure.client.logistics.model.ExpressTrace;

/**
 * 物流查询 SPI（架构 5.1 / SRS FR-12）。
 *
 * <p><b>注意（BR-04）：</b>本接口及其 Mock 实现<b>不</b>实现 {@code Tool} 接口，因此<b>不</b>会被
 * {@code ToolRegistry} 注册、也<b>不</b>会下发模型。物流能力是否暴露给模型，由是否包装为 Tool 决定，
 * MVP 阶段仅保留 SPI 契约与 Mock 结果集，杜绝模型编造"已查询物流"。
 */
public interface LogisticsClient {

    /**
     * 查询轨迹。
     *
     * @param companyCode 快递公司编码
     * @param trackingNo  运单号
     * @return 轨迹（查询不到时 found=false）
     */
    ExpressTrace query(String companyCode, String trackingNo);
}
