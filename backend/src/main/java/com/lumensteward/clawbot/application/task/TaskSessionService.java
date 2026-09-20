package com.lumensteward.clawbot.application.task;

import com.lumensteward.clawbot.domain.model.TaskContext;

import java.util.List;
import java.util.Optional;

/**
 * 任务型多步会话服务（SRS FR-24 / BR-33 / BR-34 / 架构 4.3 状态机）。
 *
 * <p>职责：在追问（缺必填参数）时为该用户维持任务状态，使用户后续轮次的补充信息可续接到原任务。
 * 任务上下文与 LLM 上下文<b>分离存储</b>（BR-34）：落 {@code wx_session.task_context} 供管理后台观测，
 * 并镜像到 Redis {@code task:{openid}}（TTL 10 分钟）作为活跃态。
 *
 * <p>核心语义：
 * <ul>
 *   <li>槽位填充优先于全量意图重识别（{@link SlotFiller}）；</li>
 *   <li>连续无效输入达阈值即放弃并给出明确说明（{@link #fillSlot}，BR-33，不出现下一次追问）；</li>
 *   <li>话题明显切换中断并清理（{@link #detectTopicSwitch}）；</li>
 *   <li>多任务并存最多 2 个（栈式，栈顶为当前任务）；超时 / Redis 不可用视为任务取消。</li>
 * </ul>
 */
public interface TaskSessionService {

    /**
     * 追问时建立或更新任务（幂等：栈顶同类型任务则合并，不重复压栈）。
     *
     * <p>写入 Redis 活跃态（失败则视为取消、返回空），并同步 {@code wx_session.state=TASKING}
     * 与 {@code task_context}。
     *
     * @param openid        用户标识
     * @param sessionId     会话 id（可空：仅写 Redis 活跃态）
     * @param taskType      任务类型（取触发工具名）
     * @param requiredSlots 必填槽位
     * @return 建立/更新后的任务；Redis 写入失败或参数缺失返回 {@link Optional#empty()}
     */
    Optional<TaskContext> createOrUpdate(String openid, Long sessionId, String taskType,
                                         List<String> requiredSlots);

    /**
     * 用户回复时尝试填充槽位（优先槽位填充）。
     *
     * @param openid      用户标识
     * @param userMessage 用户消息
     * @return 填充结果（无任务 / 未齐备续问 / 齐备 / 无效重问 / 放弃 / 话题切换）
     */
    SlotFillOutcome fillSlot(String openid, String userMessage);

    /**
     * 槽位齐备后取回可续接的任务并清理（出栈）。
     *
     * @param openid 用户标识
     * @return 已齐备并被清理的任务上下文；无齐备任务返回 {@link Optional#empty()}
     */
    Optional<TaskContext> resume(String openid);

    /**
     * 当前（栈顶）活跃任务。
     *
     * @param openid 用户标识
     * @return 栈顶任务；无活跃任务返回 {@link Optional#empty()}
     */
    Optional<TaskContext> current(String openid);

    /**
     * 判定用户消息是否构成对当前任务的话题明显切换。
     *
     * @param openid      用户标识
     * @param userMessage 用户消息
     * @return 明显切换到其他意图返回 true
     */
    boolean detectTopicSwitch(String openid, String userMessage);

    /**
     * 放弃当前任务（清理活跃态并同步会话状态为空闲）。
     *
     * @param openid 用户标识
     * @return 被放弃的任务；无活跃任务返回 {@link Optional#empty()}
     */
    Optional<TaskContext> abandon(String openid);

    /**
     * 按会话读取任务进度（供管理后台只读展示）。
     *
     * @param sessionId 会话主键
     * @return 会话任务视图（含 state 与 task_context）；无记录返回 {@link Optional#empty()}
     */
    Optional<SessionTaskView> findBySession(Long sessionId);

    /**
     * 按会话手动放弃任务（供管理后台）。
     *
     * @param sessionId 会话主键
     * @return 被放弃的任务；无任务返回 {@link Optional#empty()}
     */
    Optional<TaskContext> abandonBySession(Long sessionId);

    /**
     * 会话任务视图（管理后台只读，{@code task} 可空，仅当 state=TASKING 但任务体缺失时）。
     *
     * @param state 会话状态（IDLE/CHATTING/TASKING/DEGRADED）
     * @param task  任务上下文（可空）
     */
    record SessionTaskView(String state, TaskContext task) {
    }
}
