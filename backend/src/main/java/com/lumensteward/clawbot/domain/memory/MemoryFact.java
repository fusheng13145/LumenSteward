package com.lumensteward.clawbot.domain.memory;

import com.lumensteward.clawbot.common.enums.MemoryKind;
import com.lumensteward.clawbot.common.enums.MemoryOrigin;
import com.lumensteward.clawbot.common.enums.MemoryStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 个人状态库条目读模型（迭代 4 W6）。
 *
 * <p>召回注入与后台查看共用；{@code openid} 为<b>原始值</b>，出参一律在
 * {@code interfaces} 层经脱敏装配（BR-21），本模型不做脱敏以免污染比较语义。
 *
 * @param id              主键
 * @param openid          所属用户
 * @param kind            条目类型
 * @param name            实体名 / 偏好键
 * @param content         事实正文
 * @param origin          来源方式
 * @param extractor       抽取器标识与版本
 * @param confidence      抽取置信度
 * @param status          状态（ACTIVE / SUPERSEDED）
 * @param supersedesId    本条覆盖掉的旧条 id（可空）
 * @param sourceSessionId 溯源会话 id
 * @param sourceTraceId   溯源链路 id
 * @param hitCount        累计出现次数
 * @param firstSeenAt     首次出现时间
 * @param lastSeenAt      最近出现时间
 */
public record MemoryFact(Long id, String openid, MemoryKind kind, String name, String content,
                         MemoryOrigin origin, String extractor, BigDecimal confidence,
                         MemoryStatus status, Long supersedesId, Long sourceSessionId,
                         String sourceTraceId, int hitCount, LocalDateTime firstSeenAt,
                         LocalDateTime lastSeenAt) {
}
