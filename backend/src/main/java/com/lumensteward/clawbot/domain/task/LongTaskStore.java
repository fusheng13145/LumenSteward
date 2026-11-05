package com.lumensteward.clawbot.domain.task;

import java.util.List;
import java.util.Optional;

/**
 * 长任务存储端口（迭代 4 W15；domain 定义、MyBatis 实现，G-14 依赖倒置）。
 *
 * <p>状态迁移全部是<b>条件 UPDATE</b>（WHERE 带前置状态），返回「是否本方生效」——
 * 这是重启续跑与投递幂等的机制底座：
 * <ul>
 *   <li>{@link #claim}：PENDING → RUNNING 乐观抢占，抢不到说明已被本进程其他线程认领；</li>
 *   <li>{@link #markDelivered}：{@code delivered_at IS NULL} 闩，多处以先到者为准，
 *       保证「不重复下发素材」（W15 交付判据）。</li>
 * </ul>
 */
public interface LongTaskStore {

    /** 新任务落库（PENDING），返回生成主键。 */
    long insert(LongTaskRecord task);

    /** 按主键读取。 */
    Optional<LongTaskRecord> find(long id);

    /** PENDING → RUNNING（attempts+1、记 started_at）；非 PENDING 返回 false。 */
    boolean claim(long id);

    /** RUNNING → SUCCEEDED 并落结果；非 RUNNING 返回 false。 */
    boolean completeSuccess(long id, String resultJson);

    /** RUNNING → FAILED 并落失败分类；非 RUNNING 返回 false。 */
    boolean completeFailure(long id, String errorType);

    /** 投递幂等闩：delivered_at 置时刻；已投递（非 NULL）返回 false。 */
    boolean markDelivered(long id);

    /** 待执行任务（PENDING，按 id 升序，截 limit）。 */
    List<LongTaskRecord> findPending(int limit);

    /** 执行成功但尚未投递的任务（崩溃在成功与投递之间的残局，恢复侧只补投不再执行）。 */
    List<LongTaskRecord> findSucceededUndelivered(int limit);

    /** 启动续跑：全部 RUNNING 重置回 PENDING（RUNNING 不跨进程存活的唯一入口）。 */
    void requeueRunning();
}
