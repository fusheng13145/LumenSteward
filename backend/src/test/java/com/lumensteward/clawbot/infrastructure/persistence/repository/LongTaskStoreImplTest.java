package com.lumensteward.clawbot.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.lumensteward.clawbot.common.enums.LongTaskStatus;
import com.lumensteward.clawbot.domain.task.LongTaskRecord;
import com.lumensteward.clawbot.infrastructure.persistence.entity.LongTaskEntity;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.LongTaskMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 长任务存储实现单测（迭代 4 W15）：条件迁移委托、实体↔领域快照映射、未完成/未投递查询。
 */
@ExtendWith(MockitoExtension.class)
class LongTaskStoreImplTest {

    @Mock
    private LongTaskMapper mapper;

    /** 未启动 MyBatis 上下文，手工注册 TableInfo 供 Lambda 条件解析。 */
    @BeforeAll
    static void initTableInfoCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                LongTaskEntity.class);
    }

    @Test
    @DisplayName("insert：以 PENDING 落库，attempts 归零，返回生成主键")
    void shouldInsertAsPendingWithZeroAttempts() {
        when(mapper.insert(org.mockito.ArgumentMatchers.any(LongTaskEntity.class))).thenAnswer(inv -> {
            LongTaskEntity e = inv.getArgument(0);
            e.setId(7L);
            return 1;
        });
        LongTaskStoreImpl store = new LongTaskStoreImpl(mapper);

        long id = store.insert(new LongTaskRecord(null, "sample_report", "openid-x",
                LongTaskStatus.PENDING, "{\"a\":1}", null, null, 5, true, "trace-1"));

        org.mockito.ArgumentCaptor<LongTaskEntity> captor =
                org.mockito.ArgumentCaptor.forClass(LongTaskEntity.class);
        org.mockito.Mockito.verify(mapper).insert(captor.capture());
        LongTaskEntity saved = captor.getValue();
        assertThat(saved.getTaskType()).isEqualTo("sample_report");
        assertThat(saved.getStatus()).isEqualTo("PENDING");
        assertThat(saved.getAttempts()).isZero();
        assertThat(saved.getDeliveredAt()).isNull();
        assertThat(saved.getTraceId()).isEqualTo("trace-1");
        assertThat(id).isEqualTo(7L);
    }

    @Test
    @DisplayName("find：实体 → 领域快照映射（delivered 由 delivered_at 推导）")
    void shouldMapEntityToRecord() {
        LongTaskEntity entity = new LongTaskEntity();
        entity.setId(3L);
        entity.setTaskType("sample_report");
        entity.setOpenid("openid-x");
        entity.setStatus("SUCCEEDED");
        entity.setResultJson("{\"ok\":1}");
        entity.setAttempts(2);
        entity.setDeliveredAt(java.time.LocalDateTime.of(2026, 11, 6, 1, 0));
        when(mapper.selectById(3L)).thenReturn(entity);
        LongTaskStoreImpl store = new LongTaskStoreImpl(mapper);

        LongTaskRecord record = store.find(3L).orElseThrow();

        assertThat(record.status()).isEqualTo(LongTaskStatus.SUCCEEDED);
        assertThat(record.attempts()).isEqualTo(2);
        assertThat(record.delivered()).isTrue();
    }

    @Test
    @DisplayName("条件迁移全部委托 Mapper 并按受影响行数 == 1 判定")
    void shouldDelegateConditionalTransitions() {
        when(mapper.claimPending(1L)).thenReturn(1);
        when(mapper.claimPending(2L)).thenReturn(0);
        when(mapper.completeSuccess(1L, "{}")).thenReturn(1);
        when(mapper.completeFailure(1L, "TIMEOUT")).thenReturn(1);
        when(mapper.markDelivered(1L)).thenReturn(1);
        LongTaskStoreImpl store = new LongTaskStoreImpl(mapper);

        assertThat(store.claim(1L)).isTrue();
        assertThat(store.claim(2L)).isFalse();
        assertThat(store.completeSuccess(1L, "{}")).isTrue();
        assertThat(store.completeFailure(1L, "TIMEOUT")).isTrue();
        assertThat(store.markDelivered(1L)).isTrue();
        store.requeueRunning();
        verify(mapper).requeueRunning();
    }

    @Test
    @DisplayName("findPending / findSucceededUndelivered：Lambda 条件走 TableInfo 解析")
    void shouldQueryPendingAndUndelivered() {
        LongTaskEntity pending = new LongTaskEntity();
        pending.setId(1L);
        pending.setTaskType("sample_report");
        pending.setOpenid("openid-x");
        pending.setStatus("PENDING");
        pending.setAttempts(0);
        when(mapper.selectList(org.mockito.ArgumentMatchers.any())).thenReturn(List.of(pending));
        LongTaskStoreImpl store = new LongTaskStoreImpl(mapper);

        List<LongTaskRecord> pendingList = store.findPending(50);
        List<LongTaskRecord> undelivered = store.findSucceededUndelivered(50);

        assertThat(pendingList).hasSize(1);
        assertThat(pendingList.get(0).status()).isEqualTo(LongTaskStatus.PENDING);
        assertThat(undelivered).hasSize(1);
    }
}
