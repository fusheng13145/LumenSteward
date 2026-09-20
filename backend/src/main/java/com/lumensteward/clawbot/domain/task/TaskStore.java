package com.lumensteward.clawbot.domain.task;

import com.lumensteward.clawbot.domain.model.TaskContext;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 任务活跃态存储端口（SRS FR-24 / BR-34）。
 *
 * <p>以 {@code task:{openid}} 为命名空间存「待续任务栈」（最多 2 个，栈式，栈顶为当前任务），
 * 与对话上下文 {@code conv:{openid}} 严格分离（BR-34）。生产实现见
 * {@code infrastructure.cache.RedisTaskStore}；测试可用内存替身。
 *
 * <p><b>故障语义：</b>Redis 不可用时 {@link #load(String)} 返回空、{@link #save} 返回 {@code false}，
 * 上层据此将任务视为「取消」（不维持跨轮状态），主链路继续（SRS 9.5）。
 */
public interface TaskStore {

    /**
     * 覆盖写入某用户的待续任务栈（带 TTL）。
     *
     * @param openid 用户标识
     * @param tasks  任务栈（栈顶=当前任务）
     * @param ttl    存活时长
     * @return 写入成功返回 true；存储不可用返回 false
     */
    boolean save(String openid, List<TaskContext> tasks, Duration ttl);

    /**
     * 载入某用户的待续任务栈。
     *
     * @param openid 用户标识
     * @return 任务栈；不存在或存储不可用返回 {@link Optional#empty()}
     */
    Optional<List<TaskContext>> load(String openid);

    /**
     * 删除某用户的待续任务栈（任务完成/放弃/超时清理）。
     *
     * @param openid 用户标识
     */
    void delete(String openid);

    /**
     * 是否存在活跃任务键。
     *
     * @param openid 用户标识
     * @return 存在返回 true
     */
    boolean exists(String openid);
}
