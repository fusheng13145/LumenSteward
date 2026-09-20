package com.lumensteward.clawbot.domain.memory;

import com.lumensteward.clawbot.common.enums.MemoryKind;
import com.lumensteward.clawbot.common.enums.MemoryOrigin;

import java.math.BigDecimal;

/**
 * 个人状态库写入意图（迭代 4 W6）。
 *
 * <p>一条「候选事实」＝ 某用户关于某个 (kind, name) 的一条陈述。写入语义由
 * {@link MemoryStore#upsert} 决定（新增 / 覆盖 / 强化），调用方不做判断。
 *
 * @param openid           所属用户（隔离键，BR-07）
 * @param kind             条目类型
 * @param name             实体名 / 偏好键
 * @param content          事实正文
 * @param origin           来源方式（自动抽取 / 工具写入）
 * @param extractor        抽取器标识与版本（可空）
 * @param confidence       抽取置信度 0.000~1.000（可空）
 * @param sourceSessionId  溯源：来源会话 id（可空）
 * @param sourceTraceId    溯源：来源链路标识（可空）
 */
public record MemoryWrite(String openid, MemoryKind kind, String name, String content,
                          MemoryOrigin origin, String extractor, BigDecimal confidence,
                          Long sourceSessionId, String sourceTraceId) {

    /** 是否为可写入的完整候选（缺一即丢弃，不落脏数据）。 */
    public boolean valid() {
        return openid != null && !openid.isBlank()
                && kind != null
                && name != null && !name.isBlank()
                && content != null && !content.isBlank()
                && origin != null;
    }
}
