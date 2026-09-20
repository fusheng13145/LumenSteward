package com.lumensteward.clawbot.domain.memory;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 个人状态库存储端口（迭代 4 W6，领域层不含框架依赖）。
 *
 * <p>实现见 {@code infrastructure/persistence/repository/MemoryStoreImpl}。写语义收敛为一句话：
 * <b>同一用户同一 (kind, name) 至多一条生效事实</b>；重复出现强化、出现新陈述则覆盖并留痕。
 *
 * <p>BR-07：一切读写以 {@code openid} 为隔离键，端口不提供「跨用户」查询。
 */
public interface MemoryStore {

    /** 一次 upsert 的结果，用于日志与测试断言。 */
    enum WriteOutcome {
        /** 新建生效条目。 */
        CREATED,
        /** 旧条目转 SUPERSEDED，新条目生效。 */
        SUPERSEDED,
        /** 与既有条目一致，仅强化（hit_count + 最近出现时间）。 */
        REINFORCED
    }

    /**
     * 写入一条候选事实（新增 / 覆盖 / 强化由本方法判定）。
     *
     * @param write 写入意图（须 {@link MemoryWrite#valid()}）
     * @return 写入结果；入参非法时返回 {@code null}
     */
    WriteOutcome upsert(MemoryWrite write);

    /**
     * 取某用户的生效条目，按最近出现时间倒序（召回用）。
     *
     * @param openid 用户标识
     * @param limit  最大条数（实现侧夹紧到正整数上限）
     * @return 条目列表；无数据或入参非法时为空列表
     */
    List<MemoryFact> recallActive(String openid, int limit);

    /**
     * 物理删除某用户的全部条目（FR-19 ② 细粒度删除，含历史 SUPERSEDED 行）。
     *
     * @param openid 用户标识
     * @return 删除行数
     */
    int deleteAllByOpenid(String openid);

    /**
     * 物理清理指定时刻之前被覆盖的历史条目（留存策略只清历史，生效事实不自动消失）。
     *
     * @param cutoff 截止时间
     * @return 删除行数
     */
    int purgeSupersededBefore(LocalDateTime cutoff);
}
