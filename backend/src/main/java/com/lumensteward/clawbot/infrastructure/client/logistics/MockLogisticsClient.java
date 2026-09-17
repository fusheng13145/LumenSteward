package com.lumensteward.clawbot.infrastructure.client.logistics;

import com.lumensteward.clawbot.common.util.MaskUtils;
import com.lumensteward.clawbot.infrastructure.client.logistics.model.ExpressTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 物流查询 Mock 实现（SRS FR-12，AC-D1：无外网）。
 *
 * <p>返回确定性轨迹；对特定运单号返回「查无数据」以覆盖 L3 空结果分支。日志脱敏运单号（BR-15）。
 */
@Component
public class MockLogisticsClient implements LogisticsClient {

    private static final Logger log = LoggerFactory.getLogger(MockLogisticsClient.class);

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @Override
    public ExpressTrace query(String companyCode, String trackingNo) {
        log.info("Mock 物流查询 company={} trackingNo={}", companyCode, MaskUtils.trackingNo(trackingNo));
        if (trackingNo == null || trackingNo.isBlank()) {
            return new ExpressTrace(companyCode, trackingNo, "无数据", false, List.of());
        }
        // 以固定前缀构造"无数据"用例，覆盖 L3 空结果重试分支
        if (trackingNo.startsWith("EMPTY")) {
            return new ExpressTrace(companyCode, trackingNo, "无数据", false, List.of());
        }
        String now = LocalDateTime.now().format(FORMATTER);
        List<ExpressTrace.Node> nodes = List.of(
                new ExpressTrace.Node(now, "已揽收", "快件已由揽收员揽收"),
                new ExpressTrace.Node(now, "运输中", "快件已到达转运中心"));
        return new ExpressTrace(companyCode, trackingNo, "运输中", true, nodes);
    }
}
