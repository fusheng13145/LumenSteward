package com.lumensteward.clawbot.application.reminder;

import com.lumensteward.clawbot.application.config.ConfigKeys;
import com.lumensteward.clawbot.application.config.DynamicConfigService;
import com.lumensteward.clawbot.application.wechat.WechatMessageService;
import com.lumensteward.clawbot.common.enums.CommitmentStatus;
import com.lumensteward.clawbot.domain.reminder.CommitmentRecord;
import com.lumensteward.clawbot.domain.reminder.CommitmentStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 到期提醒调度器测试（迭代 4 W7/W8）：到点投递、48h 预算、投递闩至多一次、开关闸门。
 *
 * <p>存储用内存桩（条件迁移与生产同语义），预算用 Mockito 桩的 StringRedisTemplate——
 * 验证的是调度器对「预算 → 闩 → 外发」顺序与幂等的状态机语义。
 */
class CommitmentReminderSchedulerTest {

    private static final String OPENID = "openid-rem-1";

    @Test
    @DisplayName("W7/W8 ①：到点承诺投递一次，文案带原内容，投递闩置位")
    void shouldDeliverDueCommitment() {
        InMemoryStore store = new InMemoryStore();
        store.seed(OPENID, "带咪咪去打疫苗", LocalDateTime.now().minusMinutes(1));
        WechatMessageService push = mock(WechatMessageService.class);
        CommitmentReminderScheduler scheduler = scheduler(store, push, null, null);

        scheduler.scan();

        verify(push, times(1)).pushAsync(eq(OPENID), contains("带咪咪去打疫苗"));
        assertThat(store.records.get(1L).status()).isEqualTo(CommitmentStatus.DELIVERED);
    }

    @Test
    @DisplayName("W7/W8 ②：未到期不投；重复扫描不重复投递（闩已置位）")
    void shouldSkipUndueAndNeverRedeliver() {
        InMemoryStore store = new InMemoryStore();
        store.seed(OPENID, "后天的事", LocalDateTime.now().plusDays(2));
        long dueId = store.seed(OPENID, "已到期的事", LocalDateTime.now().minusMinutes(5));
        WechatMessageService push = mock(WechatMessageService.class);
        CommitmentReminderScheduler scheduler = scheduler(store, push, null, null);

        scheduler.scan();
        scheduler.scan();
        scheduler.scan();

        verify(push, times(1)).pushAsync(anyString(), contains("已到期的事"));
        assertThat(store.records.get(dueId).status()).isEqualTo(CommitmentStatus.DELIVERED);
    }

    @Test
    @DisplayName("W7/W8 ③：48h 预算内放行并计数；超限的承诺留 PENDING 不投")
    void shouldEnforcePer48hBudget() {
        InMemoryStore store = new InMemoryStore();
        for (int i = 1; i <= 3; i++) {
            store.seed(OPENID, "第 " + i + " 件到期的事", LocalDateTime.now().minusMinutes(i));
        }
        WechatMessageService push = mock(WechatMessageService.class);
        DynamicConfigService config = config(Map.of("reminder.max-per-48h", "2"));
        Map<String, String> redis = new HashMap<>();
        CommitmentReminderScheduler scheduler = scheduler(store, push, config, redisStub(redis));

        scheduler.scan();

        // 前 2 条在预算内投递，第 3 条超限留 PENDING
        verify(push, times(2)).pushAsync(eq(OPENID), anyString());
        assertThat(store.records.values().stream().filter(r -> r.status() == CommitmentStatus.DELIVERED).count())
                .isEqualTo(2);
        assertThat(store.records.values().stream().filter(r -> r.status() == CommitmentStatus.PENDING).count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("W7/W8 ④：开关关闭时整轮扫描跳过；Redis 故障时预算退化为不限制（fail-open）")
    void shouldRespectEnabledGateAndFailOpenBudget() {
        InMemoryStore store = new InMemoryStore();
        store.seed(OPENID, "到期的事", LocalDateTime.now().minusMinutes(1));
        WechatMessageService push = mock(WechatMessageService.class);
        DynamicConfigService off = config(Map.of("reminder.enabled", "false"));
        CommitmentReminderScheduler offScheduler = scheduler(store, push, off, null);

        offScheduler.scan();
        verify(push, never()).pushAsync(anyString(), anyString());

        // fail-open：预算读取抛异常 → 仍投递
        WechatMessageService push2 = mock(WechatMessageService.class);
        StringRedisTemplate broken = org.mockito.Mockito.mock(StringRedisTemplate.class);
        when(broken.opsForValue()).thenThrow(new IllegalStateException("redis down"));
        CommitmentReminderScheduler failOpen = scheduler(store, push2, null, broken);
        failOpen.scan();
        verify(push2, times(1)).pushAsync(eq(OPENID), anyString());
    }

    @Test
    @DisplayName("W7/W8 ⑤：预算键首写设 48h TTL，键名 rem:48h:{openid}")
    void shouldWriteBudgetKeyWith48hTtl() {
        InMemoryStore store = new InMemoryStore();
        store.seed(OPENID, "到期的事", LocalDateTime.now().minusMinutes(1));
        WechatMessageService push = mock(WechatMessageService.class);
        StringRedisTemplate redis = org.mockito.Mockito.mock(StringRedisTemplate.class);
        org.springframework.data.redis.core.ValueOperations<String, String> ops =
                org.mockito.Mockito.mock(org.springframework.data.redis.core.ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(CommitmentReminderScheduler.BUDGET_KEY_PREFIX + OPENID)).thenReturn(null);
        CommitmentReminderScheduler scheduler = scheduler(store, push, null, redis);

        scheduler.scan();

        verify(ops).set(CommitmentReminderScheduler.BUDGET_KEY_PREFIX + OPENID, "1",
                java.time.Duration.ofHours(48));
        verify(push, times(1)).pushAsync(eq(OPENID), anyString());
    }

    // ===== 桩与工具 =====

    private CommitmentReminderScheduler scheduler(InMemoryStore store, WechatMessageService push,
                                                  DynamicConfigService config,
                                                  StringRedisTemplate redis) {
        return new CommitmentReminderScheduler(store, push, config, redis);
    }

    /** 带真实计数语义的 Redis 桩：get/set(48h TTL)/increment 落在内存 map。 */
    @SuppressWarnings("unchecked")
    private StringRedisTemplate redisStub(Map<String, String> backing) {
        StringRedisTemplate template = mock(StringRedisTemplate.class);
        org.springframework.data.redis.core.ValueOperations<String, String> ops =
                mock(org.springframework.data.redis.core.ValueOperations.class);
        when(template.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenAnswer(inv -> backing.get((String) inv.getArgument(0)));
        org.mockito.Mockito.doAnswer(inv -> {
            backing.put((String) inv.getArgument(0), (String) inv.getArgument(1));
            return null;
        }).when(ops).set(anyString(), anyString(), org.mockito.ArgumentMatchers.any(java.time.Duration.class));
        when(ops.increment(anyString())).thenAnswer(inv -> {
            String key = (String) inv.getArgument(0);
            long value = Long.parseLong(backing.getOrDefault(key, "0")) + 1;
            backing.put(key, String.valueOf(value));
            return value;
        });
        return template;
    }

    private DynamicConfigService config(Map<String, String> values) {
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
                if (v == null) {
                    return fallback;
                }
                return switch (key) {
                    case ConfigKeys.MEMORY_GROWTH_MAX_ITEMS, ConfigKeys.REMINDER_MAX_PER_48H ->
                            Integer.parseInt(v.trim());
                    default -> fallback;
                };
            }

            @Override
            public long getLong(String key, long fallback) {
                return fallback;
            }

            @Override
            public boolean getBoolean(String key, boolean fallback) {
                String v = values.get(key);
                if (v == null) {
                    return fallback;
                }
                return switch (key) {
                    case ConfigKeys.REMINDER_ENABLED -> Boolean.parseBoolean(v.trim());
                    default -> fallback;
                };
            }

            @Override
            public List<String> getList(String key, List<String> fallback) {
                return fallback;
            }

            @Override
            public java.util.Set<String> getSet(String key, java.util.Set<String> fallback) {
                return fallback;
            }

            @Override
            public boolean isAvailable() {
                return true;
            }
        };
    }

    /** 内存承诺存储桩：投递闩条件迁移与生产同语义。 */
    private static final class InMemoryStore implements CommitmentStore {
        private final Map<Long, CommitmentRecord> records = new HashMap<>();
        private long seq = 0;

        long seed(String openid, String content, LocalDateTime dueAt) {
            long id = ++seq;
            records.put(id, new CommitmentRecord(id, openid, content, dueAt,
                    CommitmentStatus.PENDING, null, null));
            return id;
        }

        @Override
        public long register(String openid, String content, LocalDateTime dueAt,
                             Long sourceSessionId, String sourceTraceId) {
            long id = ++seq;
            records.put(id, new CommitmentRecord(id, openid, content, dueAt,
                    CommitmentStatus.PENDING, sourceSessionId, sourceTraceId));
            return id;
        }

        @Override
        public Optional<CommitmentRecord> find(long id) {
            return Optional.ofNullable(records.get(id));
        }

        @Override
        public boolean markDelivered(long id) {
            CommitmentRecord record = records.get(id);
            if (record == null || record.delivered()) {
                return false;
            }
            records.put(id, new CommitmentRecord(record.id(), record.openid(), record.content(),
                    record.dueAt(), CommitmentStatus.DELIVERED, record.sourceSessionId(),
                    record.sourceTraceId()));
            return true;
        }

        @Override
        public List<CommitmentRecord> findDue(LocalDateTime cutoff, int limit) {
            List<CommitmentRecord> out = new ArrayList<>();
            for (CommitmentRecord record : records.values()) {
                if (record.status() == CommitmentStatus.PENDING && !record.dueAt().isAfter(cutoff)) {
                    out.add(record);
                }
            }
            out.sort(java.util.Comparator.comparing(CommitmentRecord::dueAt));
            return out;
        }

        @Override
        public List<CommitmentRecord> findByOpenid(String openid, int limit) {
            return List.of();
        }
    }
}
