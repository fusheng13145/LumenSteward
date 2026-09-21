package com.lumensteward.clawbot.interfaces.dto.memory;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 个人状态库条目视图（迭代 4 W6-b / GET /api/memories）。
 *
 * @param id              主键
 * @param openid          脱敏后的所属用户标识（BR-21）
 * @param kind            条目类型：PERSON/PLACE/THING/PREFERENCE/HABIT/FACT
 * @param name            实体名 / 偏好键
 * @param content         事实正文（派生 PII，仅管理侧可见）
 * @param origin          来源方式：AUTO_EXTRACT / TOOL
 * @param extractor       抽取器标识与版本
 * @param confidence      抽取置信度 0.000~1.000
 * @param sourceSessionId 溯源：来源会话 id
 * @param sourceTraceId   溯源：来源链路标识
 * @param status          状态：ACTIVE / SUPERSEDED
 * @param supersedesId    本条覆盖掉的旧条 id
 * @param hitCount        累计出现次数
 * @param firstSeenAt     首次出现时间
 * @param lastSeenAt      最近出现时间
 */
public record MemoryItemVO(Long id,
                           String openid,
                           String kind,
                           String name,
                           String content,
                           String origin,
                           String extractor,
                           BigDecimal confidence,
                           Long sourceSessionId,
                           String sourceTraceId,
                           String status,
                           Long supersedesId,
                           Integer hitCount,
                           LocalDateTime firstSeenAt,
                           LocalDateTime lastSeenAt) {
}
