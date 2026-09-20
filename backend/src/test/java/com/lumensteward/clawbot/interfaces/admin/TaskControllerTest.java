package com.lumensteward.clawbot.interfaces.admin;

import com.lumensteward.clawbot.application.task.TaskSessionService;
import com.lumensteward.clawbot.common.api.ApiResponse;
import com.lumensteward.clawbot.domain.model.TaskContext;
import com.lumensteward.clawbot.interfaces.dto.session.TaskContextVO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 任务型会话控制器测试（SRS FR-24 验收④：管理后台可看到处于 TASKING 的会话）。
 *
 * <p>以 Mockito 桩替代 {@link TaskSessionService}，验证 VO 装配与会话不存在时的空数据契约。
 */
class TaskControllerTest {

    private final TaskSessionService service = mock(TaskSessionService.class);
    private final TaskController controller = new TaskController(service);

    @Test
    @DisplayName("④ 可读取处于 TASKING 的会话及其任务进度")
    void shouldReturnTaskingSessionWithProgress() {
        TaskContext task = new TaskContext("query_express", List.of("tracking_no"),
                Map.of("company_code", "SF"), Instant.parse("2025-01-01T00:10:00Z"),
                1, 0, Instant.parse("2025-01-01T00:00:00Z"));
        when(service.findBySession(7L))
                .thenReturn(Optional.of(new TaskSessionService.SessionTaskView("TASKING", task)));

        ApiResponse<TaskContextVO> response = controller.get(7L);

        assertThat(response.getData()).isNotNull();
        assertThat(response.getData().state()).isEqualTo("TASKING");
        assertThat(response.getData().taskType()).isEqualTo("query_express");
        assertThat(response.getData().requiredSlots()).containsExactly("tracking_no");
        assertThat(response.getData().filledSlots()).containsEntry("company_code", "SF");
        assertThat(response.getData().expireAt()).isEqualTo(Instant.parse("2025-01-01T00:10:00Z"));
    }

    @Test
    @DisplayName("会话不存在时返回 data=null")
    void shouldReturnNullWhenSessionAbsent() {
        when(service.findBySession(99L)).thenReturn(Optional.empty());
        assertThat(controller.get(99L).getData()).isNull();
    }

    @Test
    @DisplayName("手动放弃任务返回是否成功")
    void shouldAbandon() {
        TaskContext task = new TaskContext("query_express", List.of("tracking_no"), Map.of(),
                Instant.parse("2025-01-01T00:10:00Z"), 1, 0, Instant.parse("2025-01-01T00:00:00Z"));
        when(service.abandonBySession(7L)).thenReturn(Optional.of(task));
        assertThat(controller.abandon(7L).getData()).isTrue();

        when(service.abandonBySession(8L)).thenReturn(Optional.empty());
        assertThat(controller.abandon(8L).getData()).isFalse();
    }
}
