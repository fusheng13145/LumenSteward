package com.lumensteward.clawbot.domain.port;

import com.lumensteward.clawbot.domain.port.model.ExpressTrace;

/**
 * 快递查询能力端口（SRS FR-12）。
 *
 * <p>上提自 {@code infrastructure/client/logistics}，由 {@code MockLogisticsClient} /
 * {@code RealLogisticsClient} 实现（TODO-04）。
 */
public interface ExpressQueryPort {

    /**
     * 查询轨迹。
     *
     * @param companyCode 快递公司编码（可由运单号前缀推断）
     * @param trackingNo  运单号（日志须脱敏，BR-15）
     * @return 轨迹（查询不到时 {@code found=false}）
     * @throws ExpressQueryException 查询异常
     */
    ExpressTrace query(String companyCode, String trackingNo) throws ExpressQueryException;
}
