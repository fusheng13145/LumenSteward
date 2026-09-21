package com.lumensteward.clawbot.application.task;

import com.lumensteward.clawbot.domain.intent.IntentClassifier;
import com.lumensteward.clawbot.domain.intent.IntentResult;
import com.lumensteward.clawbot.domain.intent.IntentType;
import com.lumensteward.clawbot.domain.model.TaskContext;
import com.lumensteward.clawbot.domain.task.TaskStore;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.WxSessionMapper;
import com.lumensteward.clawbot.support.ToolRegistries;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 任务型多步会话服务测试（SRS FR-24 / BR-33 / BR-34 / T8）。
 *
 * <p>覆盖验收准则：① 槽位填充续接、② 连续 3 次无效即放弃（不出现第 4 次追问）、
 * ③ 超时清理（{@code task:{openid}} 键被清除）；另覆盖话题切换、多任务上限与无效计数清零。
 * 以内存 {@link TaskStore} 替身与可拨时钟 {@link MutableClock} 保证确定性（Redis 键行为见
 * {@code RedisTaskStoreTest}）。
 */
class TaskSessionServiceImplTest {

    private static final String OPENID = "openid-fr24-user";

    private final MutableClock clock = new MutableClock(Instant.parse("2025-01-01T00:00:00Z"));
    private final InMemoryTaskStore store = new InMemoryTaskStore();
    private final WxSessionMapper sessionMapper = mock(WxSessionMapper.class);
    private final SlotFiller slotFiller = new RuleBasedSlotFiller();

    /** 默认意图分类器：一律 UNKNOWN（不触发话题切换）。 */
    private IntentClassifier classifier = (history, message) -> IntentResult.unknown();

    private TaskSessionServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new TaskSessionServiceImpl(store, sessionMapper, slotFiller, classifier, null,
                ToolRegistries.productionTools(), clock);
    }

    @Test
    @DisplayName("① 快递缺单号建任务→用户仅回复单号→槽位齐备可续接")
    void shouldFillSlotAndResumeWhenUserRepliesTrackingNo() {
        Optional<TaskContext> created = service.createOrUpdate(OPENID, 10L, "query_express",
                List.of("tracking_no"));
        assertThat(created).isPresent();
        assertThat(created.get().taskType()).isEqualTo("query_express");
        assertThat(created.get().complete()).isFalse();
        assertThat(created.get().pendingSlots()).containsExactly("tracking_no");
        assertThat(store.exists(OPENID)).isTrue();

        SlotFillOutcome outcome = service.fillSlot(OPENID, "SF1234567890");
        assertThat(outcome.status()).isEqualTo(SlotFillOutcome.Status.SLOT_FILLED_COMPLETE);
        assertThat(outcome.context().filledSlots()).containsEntry("tracking_no", "SF1234567890");

        Optional<TaskContext> resumed = service.resume(OPENID);
        assertThat(resumed).isPresent();
        assertThat(resumed.get().filledSlots()).containsEntry("tracking_no", "SF1234567890");
        // 续接后任务清理（活跃态键消失）
        assertThat(service.current(OPENID)).isEmpty();
        assertThat(store.exists(OPENID)).isFalse();
    }

    @Test
    @DisplayName("② 连续 3 次无效输入后放弃并给明确说明，无第 4 次追问（BR-33）")
    void shouldAbandonAfterThreeInvalidAttempts() {
        service.createOrUpdate(OPENID, 11L, "query_express", List.of("tracking_no"));

        SlotFillOutcome first = service.fillSlot(OPENID, "你好呀");
        assertThat(first.status()).isEqualTo(SlotFillOutcome.Status.INVALID_REPROMPT);

        SlotFillOutcome second = service.fillSlot(OPENID, "在吗");
        assertThat(second.status()).isEqualTo(SlotFillOutcome.Status.INVALID_REPROMPT);

        SlotFillOutcome third = service.fillSlot(OPENID, "嗯嗯");
        assertThat(third.status()).isEqualTo(SlotFillOutcome.Status.ABANDONED);
        assertThat(third.replyText()).contains("暂时没法完成");
        // 放弃后任务清理
        assertThat(store.exists(OPENID)).isFalse();
        // 第 4 次不再是追问（任务已终结）
        assertThat(service.fillSlot(OPENID, "嗯").status()).isEqualTo(SlotFillOutcome.Status.NO_TASK);
    }

    @Test
    @DisplayName("③ 任务超时后 Redis 键被清理（task:{openid} 不存在）")
    void shouldClearTaskKeyOnTimeout() {
        service.createOrUpdate(OPENID, 12L, "query_express", List.of("tracking_no"));
        assertThat(store.exists(OPENID)).isTrue();

        clock.advance(Duration.ofMinutes(11));

        assertThat(service.current(OPENID)).isEmpty();
        assertThat(store.exists(OPENID)).isFalse();
        assertThat(store.load(OPENID)).isEmpty();
    }

    @Test
    @DisplayName("话题明显切换：中断并清理任务并给出说明")
    void shouldInterruptOnTopicSwitch() {
        IntentClassifier petClassifier =
                (history, message) -> new IntentResult(IntentType.PET_PROFILE, 0.95, Map.of());
        service = new TaskSessionServiceImpl(store, sessionMapper, slotFiller, petClassifier, null,
                ToolRegistries.productionTools(), clock);
        service.createOrUpdate(OPENID, 13L, "query_express", List.of("tracking_no"));

        SlotFillOutcome outcome = service.fillSlot(OPENID, "帮我记一下我的猫叫咪咪");

        assertThat(outcome.status()).isEqualTo(SlotFillOutcome.Status.TOPIC_SWITCH);
        assertThat(outcome.replyText()).contains("已暂停");
        assertThat(store.exists(OPENID)).isFalse();
    }

    @Test
    @DisplayName("多任务并存最多 2 个（栈式，栈顶为当前任务）")
    void shouldCapConcurrentTasksAtTwo() {
        service.createOrUpdate(OPENID, 14L, "query_express", List.of("tracking_no"));
        service.createOrUpdate(OPENID, 14L, "manage_pet_profile", List.of("pet_name"));
        service.createOrUpdate(OPENID, 14L, "plan_route", List.of("destination"));

        List<TaskContext> stack = store.load(OPENID).orElseThrow();
        assertThat(stack).hasSize(2);
        assertThat(stack.get(stack.size() - 1).taskType()).isEqualTo("plan_route");
        assertThat(service.current(OPENID).orElseThrow().taskType()).isEqualTo("plan_route");
    }

    @Test
    @DisplayName("有效填充清零无效计数；槽位未齐备继续追问")
    void shouldResetInvalidAttemptsOnSuccessAndRepromptWhenIncomplete() {
        service.createOrUpdate(OPENID, 15L, "query_express", List.of("tracking_no", "company_code"));

        // 一次无效
        assertThat(service.fillSlot(OPENID, "喂").status())
                .isEqualTo(SlotFillOutcome.Status.INVALID_REPROMPT);
        // 有效填充：tracking_no 命中，company_code 待填（未齐备）
        SlotFillOutcome partial = service.fillSlot(OPENID, "SF1234567890");
        assertThat(partial.status()).isEqualTo(SlotFillOutcome.Status.SLOT_FILLED_INCOMPLETE);
        assertThat(partial.context().invalidAttempts()).isZero();
        // 补齐 company_code（通用槽位）
        assertThat(service.fillSlot(OPENID, "顺丰").status())
                .isEqualTo(SlotFillOutcome.Status.SLOT_FILLED_COMPLETE);
    }

    @Test
    @DisplayName("同类型任务重复建任务为幂等更新（不重复压栈）")
    void shouldIdempotentlyUpdateSameTaskType() {
        service.createOrUpdate(OPENID, 16L, "query_express", List.of("tracking_no"));
        service.createOrUpdate(OPENID, 16L, "query_express", List.of("tracking_no"));
        assertThat(store.load(OPENID).orElseThrow()).hasSize(1);
    }

    // ===== 测试替身 =====

    /** 可拨动的时钟。 */
    static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    /** 内存任务栈（模拟 Redis {@code task:{openid}}；TTL 语义由服务按 {@link MutableClock} 判定）。 */
    static final class InMemoryTaskStore implements TaskStore {
        private final Map<String, List<TaskContext>> data = new HashMap<>();

        @Override
        public boolean save(String openid, List<TaskContext> tasks, Duration ttl) {
            data.put(openid, new ArrayList<>(tasks));
            return true;
        }

        @Override
        public Optional<List<TaskContext>> load(String openid) {
            List<TaskContext> value = data.get(openid);
            return value == null || value.isEmpty() ? Optional.empty() : Optional.of(new ArrayList<>(value));
        }

        @Override
        public void delete(String openid) {
            data.remove(openid);
        }

        @Override
        public boolean exists(String openid) {
            List<TaskContext> value = data.get(openid);
            return value != null && !value.isEmpty();
        }
    }
}
