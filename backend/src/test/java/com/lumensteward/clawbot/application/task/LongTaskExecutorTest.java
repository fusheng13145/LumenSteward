package com.lumensteward.clawbot.application.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.lumensteward.clawbot.application.config.DynamicConfigService;
import com.lumensteward.clawbot.application.wechat.WechatMessageService;
import com.lumensteward.clawbot.common.enums.LongTaskStatus;
import com.lumensteward.clawbot.domain.task.LongTaskRecord;
import com.lumensteward.clawbot.domain.task.LongTaskStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * 长任务执行器测试（迭代 4 W15）：正常执行、重启续跑、投递幂等闩、超时与无处理器路径。
 *
 * <p>存储用内存桩（与生产同语义的条件迁移），推送用 Mockito 记账——验证的是
 * 执行器对「至多一次投递」与「RUNNING 不跨进程存活」的状态机语义。
 */
class LongTaskExecutorTest {

    private static final String OPENID = "openid-lt-1";

    @Test
    @DisplayName("W15 ①：新任务完成执行并投递一次（describe 文本经客服消息外发）")
    void shouldExecuteAndDeliverOnce() {
        InMemoryStore store = new InMemoryStore();
        long id = store.seedPENDING("sample_report", "{\"title\":\"周报\",\"delay_seconds\":0}");
        WechatMessageService push = mock(WechatMessageService.class);
        LongTaskExecutor executor = executor(store, push, null, new SampleReportHandler());

        executor.dispatch(id);
        awaitAtMost(() -> store.find(id).orElseThrow().delivered());

        verify(push, timeout(5_000).times(1)).pushAsync(anyString(), anyString());
        LongTaskRecord done = store.find(id).orElseThrow();
        assertThat(done.status()).isEqualTo(LongTaskStatus.SUCCEEDED);
        assertThat(done.resultJson()).contains("周报").contains("mock-demo");
        assertThat(done.attempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("W15 ②：重启续跑——RUNNING 残局重置回 PENDING 后重新执行（attempts +1）")
    void shouldRequeueRunningOnStartupAndReexecute() {
        InMemoryStore store = new InMemoryStore();
        long id = store.seedWithStatus("sample_report", LongTaskStatus.RUNNING,
                "{\"title\":\"残局\",\"delay_seconds\":0}");
        WechatMessageService push = mock(WechatMessageService.class);
        LongTaskExecutor executor = executor(store, push, null, new SampleReportHandler());

        executor.run(null);
        awaitAtMost(() -> store.find(id).orElseThrow().delivered());

        verify(push, timeout(5_000).times(1)).pushAsync(anyString(), anyString());
        LongTaskRecord done = store.find(id).orElseThrow();
        assertThat(done.status()).isEqualTo(LongTaskStatus.SUCCEEDED);
        assertThat(done.attempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("W15 ③：成功未投递残局只补投不再执行；重复扫描零重复投递（幂等闩）")
    void shouldDeliverUndeliveredResultExactlyOnce() {
        InMemoryStore store = new InMemoryStore();
        store.seedSucceededUndelivered("sample_report", "{\"title\":\"已成功\",\"data_source\":\"mock-demo\"}");
        WechatMessageService push = mock(WechatMessageService.class);
        AtomicInteger executions = new AtomicInteger();
        LongTaskExecutor executor = executor(store, push, null, countingHandler(executions));

        executor.deliverSucceededUndelivered();
        executor.deliverSucceededUndelivered();

        verify(push, timeout(5_000).times(1)).pushAsync(anyString(), anyString());
        assertThat(executions.get()).as("残局补投不得重新执行").isZero();
    }

    @Test
    @DisplayName("W15 ④：执行超时 → FAILED/TIMEOUT 并投递失败告知")
    void shouldFailWithTimeoutWhenHandlerExceedsBudget() {
        InMemoryStore store = new InMemoryStore();
        long id = store.seedPENDING("sample_report", "{\"delay_seconds\":10}");
        WechatMessageService push = mock(WechatMessageService.class);
        DynamicConfigService config = config(Map.of("longtask.timeout-seconds", "1"));
        LongTaskExecutor executor = executor(store, push, config, new SampleReportHandler());

        executor.dispatch(id);
        awaitAtMost(() -> store.find(id).orElseThrow().status() == LongTaskStatus.FAILED);

        verify(push, timeout(5_000).times(1)).pushAsync(anyString(), anyString());
        LongTaskRecord failed = store.find(id).orElseThrow();
        assertThat(failed.errorType()).isEqualTo("TIMEOUT");
    }

    @Test
    @DisplayName("W15 ⑤：类型无处理器 → FAILED/NO_HANDLER；claim 失败则不执行")
    void shouldFailNoHandlerAndSkipWhenClaimLost() {
        InMemoryStore store = new InMemoryStore();
        long unknownTypeId = store.seedPENDING("ghost_type", "{}");
        long contestedId = store.seedPENDING("sample_report", "{}");
        store.claimed.put(contestedId, true);
        WechatMessageService push = mock(WechatMessageService.class);
        LongTaskExecutor executor = executor(store, push, null, new SampleReportHandler());

        executor.dispatch(unknownTypeId);
        executor.dispatch(contestedId);
        awaitAtMost(() -> store.find(unknownTypeId).orElseThrow().delivered());

        assertThat(store.find(unknownTypeId).orElseThrow().errorType()).isEqualTo("NO_HANDLER");
        verify(push, timeout(5_000).times(1)).pushAsync(anyString(), anyString());
        assertThat(store.find(contestedId).orElseThrow().status())
                .as("claim 失败（已被认领）不得推进状态").isEqualTo(LongTaskStatus.PENDING);
    }

    @Test
    @DisplayName("W15 ⑥：任务类型重复注册阻止启动（MessageDispatcher 同一口径）")
    void shouldRejectDuplicateHandlerType() {
        InMemoryStore store = new InMemoryStore();
        WechatMessageService push = mock(WechatMessageService.class);

        assertThatThrownBy(() -> executor(store, push, null,
                new SampleReportHandler(), new SampleReportHandler()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("长任务类型重复注册");
    }

    // ===== 桩与工具 =====

    private static LongTaskExecutor executor(InMemoryStore store, WechatMessageService push,
                                             DynamicConfigService config, LongTaskHandler... handlers) {
        return new LongTaskExecutor(store, push, config, List.of(handlers));
    }

    private static LongTaskHandler countingHandler(AtomicInteger counter) {
        return new LongTaskHandler() {
            @Override
            public String type() {
                return "sample_report";
            }

            @Override
            public JsonNode execute(JsonNode payload) {
                counter.incrementAndGet();
                return payload;
            }
        };
    }

    private static DynamicConfigService config(Map<String, String> values) {
        return new DynamicConfigService() {
            @Override
            public String get(String key) {
                return values.get(key);
            }

            @Override
            public String getString(String key, String fallback) {
                String v = values.get(key);
                return v == null || v.isBlank() ? fallback : v;
            }

            @Override
            public int getInt(String key, int fallback) {
                String v = values.get(key);
                return v == null ? fallback : Integer.parseInt(v.trim());
            }

            @Override
            public long getLong(String key, long fallback) {
                return fallback;
            }

            @Override
            public boolean getBoolean(String key, boolean fallback) {
                return fallback;
            }

            @Override
            public List<String> getList(String key, List<String> fallback) {
                return fallback;
            }

            @Override
            public Set<String> getSet(String key, Set<String> fallback) {
                return fallback;
            }

            @Override
            public boolean isAvailable() {
                return true;
            }
        };
    }

    private static void awaitAtMost(java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("等待超时：条件 10 秒内未满足");
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    /** 内存长任务存储桩：条件迁移与生产同语义（claim 仅 PENDING、delivered_at 闩）。 */
    private static final class InMemoryStore implements LongTaskStore {
        private final Map<Long, LongTaskRecord> tasks = new java.util.concurrent.ConcurrentHashMap<>();
        private final Map<Long, Boolean> claimed = new java.util.concurrent.ConcurrentHashMap<>();
        private long seq = 0;

        long seedPENDING(String type, String payload) {
            return seedWithStatus(type, LongTaskStatus.PENDING, payload);
        }

        long seedWithStatus(String type, LongTaskStatus status, String payload) {
            long id = ++seq;
            tasks.put(id, new LongTaskRecord(id, type, OPENID, status, payload,
                    null, null, 0, false, "trace-test"));
            return id;
        }

        void seedSucceededUndelivered(String type, String resultJson) {
            long id = ++seq;
            tasks.put(id, new LongTaskRecord(id, type, OPENID, LongTaskStatus.SUCCEEDED,
                    null, resultJson, null, 1, false, "trace-test"));
        }

        @Override
        public long insert(LongTaskRecord task) {
            long id = ++seq;
            tasks.put(id, new LongTaskRecord(id, task.taskType(), task.openid(),
                    LongTaskStatus.PENDING, task.payloadJson(), null, null, 0, false, task.traceId()));
            return id;
        }

        @Override
        public Optional<LongTaskRecord> find(long id) {
            return Optional.ofNullable(tasks.get(id));
        }

        @Override
        public boolean claim(long id) {
            LongTaskRecord task = tasks.get(id);
            if (task == null || task.status() != LongTaskStatus.PENDING
                    || Boolean.TRUE.equals(claimed.get(id))) {
                return false;
            }
            claimed.put(id, true);
            tasks.put(id, new LongTaskRecord(id, task.taskType(), task.openid(),
                    LongTaskStatus.RUNNING, task.payloadJson(), task.resultJson(), task.errorType(),
                    task.attempts() + 1, task.delivered(), task.traceId()));
            return true;
        }

        @Override
        public boolean completeSuccess(long id, String resultJson) {
            LongTaskRecord task = tasks.get(id);
            if (task == null || task.status() != LongTaskStatus.RUNNING) {
                return false;
            }
            tasks.put(id, new LongTaskRecord(id, task.taskType(), task.openid(),
                    LongTaskStatus.SUCCEEDED, task.payloadJson(), resultJson, null,
                    task.attempts(), task.delivered(), task.traceId()));
            return true;
        }

        @Override
        public boolean completeFailure(long id, String errorType) {
            LongTaskRecord task = tasks.get(id);
            if (task == null || task.status() != LongTaskStatus.RUNNING) {
                return false;
            }
            tasks.put(id, new LongTaskRecord(id, task.taskType(), task.openid(),
                    LongTaskStatus.FAILED, task.payloadJson(), task.resultJson(), errorType,
                    task.attempts(), task.delivered(), task.traceId()));
            return true;
        }

        @Override
        public boolean markDelivered(long id) {
            LongTaskRecord task = tasks.get(id);
            if (task == null || task.delivered()) {
                return false;
            }
            tasks.put(id, new LongTaskRecord(id, task.taskType(), task.openid(),
                    task.status(), task.payloadJson(), task.resultJson(), task.errorType(),
                    task.attempts(), true, task.traceId()));
            return true;
        }

        @Override
        public List<LongTaskRecord> findPending(int limit) {
            List<LongTaskRecord> out = new ArrayList<>();
            for (LongTaskRecord task : tasks.values()) {
                if (task.status() == LongTaskStatus.PENDING) {
                    out.add(task);
                }
            }
            return out;
        }

        @Override
        public List<LongTaskRecord> findSucceededUndelivered(int limit) {
            List<LongTaskRecord> out = new ArrayList<>();
            for (LongTaskRecord task : tasks.values()) {
                if (task.status() == LongTaskStatus.SUCCEEDED && !task.delivered()) {
                    out.add(task);
                }
            }
            return out;
        }

        @Override
        public void requeueRunning() {
            List<Long> runningIds = new ArrayList<>();
            for (LongTaskRecord task : tasks.values()) {
                if (task.status() == LongTaskStatus.RUNNING) {
                    runningIds.add(task.id());
                }
            }
            for (Long id : runningIds) {
                LongTaskRecord task = tasks.get(id);
                tasks.put(id, new LongTaskRecord(id, task.taskType(), task.openid(),
                        LongTaskStatus.PENDING, task.payloadJson(), task.resultJson(), task.errorType(),
                        task.attempts(), task.delivered(), task.traceId()));
            }
        }
    }
}
