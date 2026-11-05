package com.lumensteward.clawbot.application.task;

import com.lumensteward.clawbot.common.exception.BizException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 长任务提交服务单测（迭代 4 W15）：类型白名单、载荷校验、落库即调度。
 */
class LongTaskServiceTest {

    @Test
    @DisplayName("W15：未知类型 / 空类型 / 非法 JSON 载荷 / 超长载荷 → 400 参数拒绝")
    void shouldRejectInvalidSubmissions() {
        LongTaskExecutor executor = new LongTaskExecutor(new MemoryStub(), pushNoop(), null,
                List.of(new SampleReportHandler()));
        LongTaskService service = new LongTaskService(new MemoryStub(), executor);

        assertThatThrownBy(() -> service.submit("ghost_type", "openid-x", "{}", "t"))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.submit(null, "openid-x", "{}", "t"))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.submit("sample_report", "openid-x", "{bad", "t"))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> service.submit("sample_report", "openid-x", "{\"a\":\"" + "字".repeat(4000) + "\"}", "t"))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("W15：合法提交 → 落库得 id 并触发调度（异步执行走执行器测试覆盖）")
    void shouldSubmitValidTask() {
        MemoryStub store = new MemoryStub();
        LongTaskExecutor executor = new LongTaskExecutor(store, pushNoop(), null,
                List.of(new SampleReportHandler()));
        LongTaskService service = new LongTaskService(store, executor);

        assertThatCode(() -> {
            long id = service.submit("sample_report", "openid-x", "{\"delay_seconds\":0}", "t-1");
            assertThat(id).isPositive();
        }).doesNotThrowAnyException();
    }

    private static com.lumensteward.clawbot.application.wechat.WechatMessageService pushNoop() {
        return org.mockito.Mockito.mock(com.lumensteward.clawbot.application.wechat.WechatMessageService.class);
    }

    /** 复用执行器测试的内存桩（同包可见性受限，这里内联最小版）。 */
    static final class MemoryStub implements com.lumensteward.clawbot.domain.task.LongTaskStore {
        private final java.util.Map<Long, com.lumensteward.clawbot.domain.task.LongTaskRecord> tasks =
                new java.util.HashMap<>();
        private long seq = 0;

        @Override
        public long insert(com.lumensteward.clawbot.domain.task.LongTaskRecord task) {
            long id = ++seq;
            tasks.put(id, task);
            return id;
        }

        @Override
        public java.util.Optional<com.lumensteward.clawbot.domain.task.LongTaskRecord> find(long id) {
            return java.util.Optional.ofNullable(tasks.get(id));
        }

        @Override
        public boolean claim(long id) {
            return true;
        }

        @Override
        public boolean completeSuccess(long id, String resultJson) {
            return true;
        }

        @Override
        public boolean completeFailure(long id, String errorType) {
            return true;
        }

        @Override
        public boolean markDelivered(long id) {
            return true;
        }

        @Override
        public java.util.List<com.lumensteward.clawbot.domain.task.LongTaskRecord> findPending(int limit) {
            return java.util.List.of();
        }

        @Override
        public java.util.List<com.lumensteward.clawbot.domain.task.LongTaskRecord> findSucceededUndelivered(int limit) {
            return java.util.List.of();
        }

        @Override
        public void requeueRunning() {
            // no-op
        }
    }
}
