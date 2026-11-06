package com.lumensteward.clawbot.domain.reminder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 承诺存储端口（迭代 4 W7/W8；domain 定义、MyBatis 实现，G-14 依赖倒置）。
 *
 * <p>投递是<b>条件 UPDATE 闩</b>（PENDING → DELIVERED），返回「是否本方生效」——
 * 与长任务通道（W15）同一幂等口径：闩抢到的一方才真正外发客服消息，保证「至多一次投递」。
 * 登记侧不做同内容去重（同一句话重复托付按多次登记处理，由 48h 预算约束总量）。
 */
public interface CommitmentStore {

    /**
     * 登记新承诺（PENDING）。
     *
     * @param openid          所属用户
     * @param content         要提醒的事（≤255 字，超长由实现截断或拒绝）
     * @param dueAt           到期时刻（已定格的绝对时间）
     * @param sourceSessionId 来源会话 id（可空）
     * @param sourceTraceId   来源链路标识（可空）
     * @return 生成的承诺 id；入参非法（openid/content 空、dueAt 缺失）返回 -1 不落库
     */
    long register(String openid, String content, LocalDateTime dueAt,
                  Long sourceSessionId, String sourceTraceId);

    /** 按主键读取。 */
    Optional<CommitmentRecord> find(long id);

    /**
     * 到期投递闩：PENDING → DELIVERED（记 delivered_at）。
     *
     * @param id 承诺 id
     * @return true=本方抢到（应真正外发）；false=已投递或不存在
     */
    boolean markDelivered(long id);

    /**
     * 扫描到期未投递的承诺（PENDING 且 due_at ≤ cutoff，按 due_at 升序）。
     *
     * @param cutoff 当前时刻
     * @param limit  上限
     */
    List<CommitmentRecord> findDue(LocalDateTime cutoff, int limit);

    /**
     * 用户全部承诺（后台/取证用，按 due_at 升序）。
     *
     * @param openid 所属用户
     * @param limit  上限
     */
    List<CommitmentRecord> findByOpenid(String openid, int limit);
}
