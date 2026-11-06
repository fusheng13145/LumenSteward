package com.lumensteward.clawbot.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.lumensteward.clawbot.common.enums.CommitmentStatus;
import com.lumensteward.clawbot.infrastructure.persistence.entity.CommitmentEntity;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.CommitmentMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 承诺存储实现单测（迭代 4 W7/W8）：登记字段、投递闩委托、到期/按用户查询。
 */
@ExtendWith(MockitoExtension.class)
class CommitmentStoreImplTest {

    @Mock
    private CommitmentMapper mapper;

    /** 未启动 MyBatis 上下文，手工注册 TableInfo 供 Lambda 条件解析。 */
    @BeforeAll
    static void initTableInfoCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                CommitmentEntity.class);
    }

    @Test
    @DisplayName("register：PENDING 落库、content 截断到 255、返回生成主键")
    void shouldRegisterAsPending() {
        when(mapper.insert(any(CommitmentEntity.class))).thenAnswer(inv -> {
            CommitmentEntity e = inv.getArgument(0);
            e.setId(9L);
            return 1;
        });
        CommitmentStoreImpl store = new CommitmentStoreImpl(mapper);

        long id = store.register("openid-x", "带咪咪去打疫苗" + "字".repeat(300),
                LocalDateTime.of(2026, 11, 6, 12, 0), 7L, "trace-1");

        ArgumentCaptor<CommitmentEntity> captor = ArgumentCaptor.forClass(CommitmentEntity.class);
        verify(mapper).insert(captor.capture());
        CommitmentEntity saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo("PENDING");
        assertThat(saved.getOpenid()).isEqualTo("openid-x");
        assertThat(saved.getContent()).hasSize(255);
        assertThat(saved.getSourceSessionId()).isEqualTo(7L);
        assertThat(id).isEqualTo(9L);
    }

    @Test
    @DisplayName("register：入参非法（openid/content/due 缺失）返回 -1 不触库")
    void shouldRejectInvalidRegistration() {
        CommitmentStoreImpl store = new CommitmentStoreImpl(mapper);

        assertThat(store.register(null, "x", LocalDateTime.now(), null, null)).isEqualTo(-1L);
        assertThat(store.register("openid-x", " ", LocalDateTime.now(), null, null)).isEqualTo(-1L);
        assertThat(store.register("openid-x", "x", null, null, null)).isEqualTo(-1L);
        verify(mapper, org.mockito.Mockito.never()).insert(any(CommitmentEntity.class));
    }

    @Test
    @DisplayName("markDelivered：按受影响行数 == 1 判定本方抢到（投递闩委托）")
    void shouldDelegateDeliveryLatch() {
        when(mapper.markDelivered(1L)).thenReturn(1);
        when(mapper.markDelivered(2L)).thenReturn(0);
        CommitmentStoreImpl store = new CommitmentStoreImpl(mapper);

        assertThat(store.markDelivered(1L)).isTrue();
        assertThat(store.markDelivered(2L)).isFalse();
    }

    @Test
    @DisplayName("findDue / findByOpenid：Lambda 条件走 TableInfo 解析并映射状态")
    void shouldQueryDueAndByOpenid() {
        CommitmentEntity due = new CommitmentEntity();
        due.setId(3L);
        due.setOpenid("openid-x");
        due.setContent("带咪咪去打疫苗");
        due.setDueAt(LocalDateTime.of(2026, 11, 6, 12, 0));
        due.setStatus("PENDING");
        when(mapper.selectList(any())).thenReturn(List.of(due));
        CommitmentStoreImpl store = new CommitmentStoreImpl(mapper);

        List<com.lumensteward.clawbot.domain.reminder.CommitmentRecord> records =
                store.findDue(LocalDateTime.now(), 50);

        assertThat(records).hasSize(1);
        assertThat(records.get(0).status()).isEqualTo(CommitmentStatus.PENDING);
        assertThat(records.get(0).delivered()).isFalse();
        assertThat(store.findByOpenid("openid-x", 10)).hasSize(1);
        assertThat(store.findByOpenid(null, 10)).isEmpty();
    }
}
