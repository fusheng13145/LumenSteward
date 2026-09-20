package com.lumensteward.clawbot.interfaces.admin;

import com.lumensteward.clawbot.application.task.TaskSessionService;
import com.lumensteward.clawbot.common.api.ApiResponse;
import com.lumensteward.clawbot.domain.model.TaskContext;
import com.lumensteward.clawbot.interfaces.dto.session.TaskContextVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 任务型会话管理控制器（SRS FR-24，迭代 3 Wave 2 / T8）。
 *
 * <p>仅提供<b>只读观测</b>与<b>手动放弃</b>：任务的建立与槽位填充由编排器在用户消息内部驱动，
 * <b>不暴露用户侧写接口</b>（避免绕过对话链路伪造任务状态）。
 *
 * <p>角色：读取允许 SUPER_ADMIN / OPERATOR / AUDITOR（与工具日志同口径）；手动放弃允许 OPERATOR+。
 */
@RestController
@RequestMapping("/api/tasks")
@Tag(name = "任务型会话", description = "任务进度观测与手动放弃（FR-24）")
public class TaskController {

    private final TaskSessionService taskSessionService;

    /**
     * 构造器注入（G-14）。
     *
     * @param taskSessionService 任务型会话服务
     */
    public TaskController(TaskSessionService taskSessionService) {
        this.taskSessionService = taskSessionService;
    }

    /**
     * 查询某会话的任务进度。
     *
     * <p>返回会话 {@code state} 与任务上下文（可能为空）；处于 {@code TASKING} 的会话即在此可见，
     * 满足「管理后台可看到处于 TASKING 的会话」（FR-24 验收④）。
     *
     * @param sessionId 会话主键
     * @return 任务进度视图；会话不存在时 {@code data=null}
     */
    @GetMapping("/{sessionId}")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','OPERATOR','AUDITOR')")
    @Operation(summary = "任务进度", description = "按会话读取 state 与任务槽位填充进度")
    public ApiResponse<TaskContextVO> get(@PathVariable Long sessionId) {
        return taskSessionService.findBySession(sessionId)
                .map(view -> ApiResponse.success(toVO(view.state(), view.task())))
                .orElseGet(() -> ApiResponse.success(null));
    }

    /**
     * 手动放弃某会话的当前任务。
     *
     * @param sessionId 会话主键
     * @return 是否放弃成功（无活跃任务或会话不存在时 data=false）
     */
    @PostMapping("/{sessionId}/abandon")
    @PreAuthorize("hasAnyRole('SUPER_ADMIN','OPERATOR')")
    @Operation(summary = "放弃任务", description = "清理活跃任务并将会话重置为空闲")
    public ApiResponse<Boolean> abandon(@PathVariable Long sessionId) {
        return ApiResponse.success(taskSessionService.abandonBySession(sessionId).isPresent());
    }

    private static TaskContextVO toVO(String state, TaskContext task) {
        if (task == null) {
            return new TaskContextVO(null, List.of(), Map.of(), null, state);
        }
        return new TaskContextVO(task.taskType(), task.requiredSlots(), task.filledSlots(),
                task.expireAt(), state);
    }
}
