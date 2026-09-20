package com.lumensteward.clawbot.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.lumensteward.clawbot.common.enums.MemoryKind;
import com.lumensteward.clawbot.common.enums.MemoryOrigin;
import com.lumensteward.clawbot.common.enums.MemoryStatus;
import com.lumensteward.clawbot.domain.memory.MemoryFact;
import com.lumensteward.clawbot.domain.memory.MemoryStore;
import com.lumensteward.clawbot.domain.memory.MemoryWrite;
import com.lumensteward.clawbot.infrastructure.persistence.entity.MemoryItemEntity;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.MemoryItemMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 个人状态库存储实现单测（迭代 4 W6：新增 / 覆盖 / 强化三态与溯源）。
 *
 * <p>写语义是 W6 的核心承诺——「同一用户同一 (kind, name) 至多一条生效事实」，
 * 且覆盖必须<b>留痕</b>而非静默丢弃。故本测试逐一断言三种结局、覆盖链字段、
 * 以及数据库唯一约束被当成「并发强化」而非失败。
 */
@ExtendWith(MockitoExtension.class)
class MemoryStoreImplTest {

    @Mock
    private MemoryItemMapper mapper;

    /** 未启动 MyBatis 上下文，手工注册 TableInfo 供 Lambda 条件解析。 */
    @BeforeAll
    static void initTableInfoCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                MemoryItemEntity.class);
    }

    private MemoryStore store() {
        return new MemoryStoreImpl(mapper);
    }

    private static MemoryWrite write(MemoryKind kind, String name, String content) {
        return new MemoryWrite("openid-1", kind, name, content, MemoryOrigin.AUTO_EXTRACT,
                "llm-extract-v1", new BigDecimal("0.8"), 42L, "trace-1");
    }

    @Test
    @DisplayName("入参不完整（缺 name）→ 直接丢弃，不触库")
    void invalidWriteIsDropped() {
        assertThat(store().upsert(write(MemoryKind.PERSON, " ", "住在北京"))).isNull();
        verify(mapper, never()).insert(any(MemoryItemEntity.class));
    }

    @Test
    @DisplayName("无同键生效条目 → 新建 ACTIVE，溯源三列齐备")
    void createsActiveItemWithProvenance() {
        when(mapper.selectOne(any())).thenReturn(null);

        assertThat(store().upsert(write(MemoryKind.PERSON, "妈妈", "住在北京")))
                .isEqualTo(MemoryStore.WriteOutcome.CREATED);

        ArgumentCaptor<MemoryItemEntity> captor = ArgumentCaptor.forClass(MemoryItemEntity.class);
        verify(mapper).insert(captor.capture());
        MemoryItemEntity saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(MemoryStatus.ACTIVE.name());
        assertThat(saved.getKind()).isEqualTo("PERSON");
        assertThat(saved.getOrigin()).isEqualTo("AUTO_EXTRACT");
        assertThat(saved.getExtractor()).isEqualTo("llm-extract-v1");
        assertThat(saved.getSourceSessionId()).isEqualTo(42L);
        assertThat(saved.getSourceTraceId()).isEqualTo("trace-1");
        assertThat(saved.getConfidence()).isEqualByComparingTo("0.8");
        assertThat(saved.getHitCount()).isEqualTo(1);
        assertThat(saved.getFirstSeenAt()).isNotNull();
        assertThat(saved.getSupersedesId()).isNull();
    }

    @Test
    @DisplayName("内容完全一致 → 只强化计数，不新增行")
    void sameContentReinforces() {
        MemoryItemEntity existing = existing(7L, "住在北京。");
        when(mapper.selectOne(any())).thenReturn(existing);

        assertThat(store().upsert(write(MemoryKind.PLACE, "老家", "住在北京。")))
                .isEqualTo(MemoryStore.WriteOutcome.REINFORCED);

        verify(mapper).reinforce(7L);
        verify(mapper, never()).insert(any(MemoryItemEntity.class));
        verify(mapper, never()).updateById(any(MemoryItemEntity.class));
    }

    @Test
    @DisplayName("出现新陈述 → 旧行先转 SUPERSEDED，新行记 supersedesId 并沿用首次出现时间")
    void newStatementSupersedesOldAndKeepsHistory() {
        LocalDateTime earlier = LocalDateTime.now().minusDays(30);
        MemoryItemEntity existing = existing(7L, "住在北京。");
        existing.setFirstSeenAt(earlier);
        when(mapper.selectOne(any())).thenReturn(existing);

        assertThat(store().upsert(write(MemoryKind.PLACE, "老家", "搬到上海了。")))
                .isEqualTo(MemoryStore.WriteOutcome.SUPERSEDED);

        // 覆盖顺序：先让旧行让出唯一槽位，再插入新行（否则撞 uk_openid_kind_name_live_marker）
        verify(mapper).updateById(any(MemoryItemEntity.class));
        ArgumentCaptor<MemoryItemEntity> captor = ArgumentCaptor.forClass(MemoryItemEntity.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getSupersedesId()).isEqualTo(7L);
        assertThat(captor.getValue().getFirstSeenAt()).isEqualTo(earlier);
        assertThat(existing.getStatus()).isEqualTo(MemoryStatus.SUPERSEDED.name());
    }

    @Test
    @DisplayName("并发下撞唯一约束（查得到他人写入的同键行）→ 视为强化，不报错")
    void duplicateKeyBecomesReinforce() {
        when(mapper.selectOne(any())).thenReturn(null, existing(9L, "住在北京。"));
        when(mapper.insert(any(MemoryItemEntity.class)))
                .thenThrow(new DuplicateKeyException("uk_openid_kind_name_live_marker"));

        assertThat(store().upsert(write(MemoryKind.PERSON, "妈妈", "住在北京")))
                .isEqualTo(MemoryStore.WriteOutcome.REINFORCED);
        verify(mapper).reinforce(9L);
    }

    @Test
    @DisplayName("撞约束却查不到对手（真实冲突）→ 原样抛出，不假装成功")
    void unexplainedDuplicatePropagates() {
        when(mapper.selectOne(any())).thenReturn(null);
        when(mapper.insert(any(MemoryItemEntity.class))).thenThrow(new DuplicateKeyException("other"));

        assertThatThrownBy(() -> store().upsert(write(MemoryKind.FACT, "k", "v")))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("召回：openid 空白不触库；越界 limit 仍照常查询（夹紧在实现侧，不外抛）")
    void recallGuardsOpenidAndToleratesOutOfRangeLimit() {
        assertThat(store().recallActive(" ", 10)).isEmpty();
        verify(mapper, never()).selectList(any());

        when(mapper.selectList(any())).thenReturn(List.of());
        assertThat(store().recallActive("openid-1", 999)).isEmpty();
        assertThat(store().recallActive("openid-1", 0)).isEmpty();
        // 两次越界调用各发出一次查询（limit 被夹紧为合法值，而非直接放弃召回）
        verify(mapper, times(2)).selectList(any());
    }

    @Test
    @DisplayName("召回：脏枚举值回落 FACT/AUTO_EXTRACT/ACTIVE，读模型不因历史脏数据中断")
    void recallToleratesUnknownEnumValues() {
        MemoryItemEntity row = existing(3L, "喜欢喝绿茶");
        row.setKind("LIKES");
        row.setOrigin("MYSTERY");
        row.setStatus("BOGUS");
        row.setHitCount(null);
        when(mapper.selectList(any())).thenReturn(List.of(row));

        List<MemoryFact> facts = store().recallActive("openid-1", 5);

        assertThat(facts).hasSize(1);
        assertThat(facts.get(0).kind()).isEqualTo(MemoryKind.PREFERENCE);
        assertThat(facts.get(0).origin()).isEqualTo(MemoryOrigin.AUTO_EXTRACT);
        assertThat(facts.get(0).status()).isEqualTo(MemoryStatus.ACTIVE);
        assertThat(facts.get(0).hitCount()).isZero();
    }

    @Test
    @DisplayName("清理入口：openid 空白 / cutoff 为 null 时不动库")
    void destructiveEntryPointsGuardNulls() {
        assertThat(store().deleteAllByOpenid(null)).isZero();
        assertThat(store().purgeSupersededBefore(null)).isZero();
        verify(mapper, never()).deleteAllByOpenid(anyString());
        verify(mapper, never()).deleteSupersededBefore(any());
        verify(mapper, never()).reinforce(anyLong());
    }

    private static MemoryItemEntity existing(Long id, String content) {
        MemoryItemEntity entity = new MemoryItemEntity();
        entity.setId(id);
        entity.setOpenid("openid-1");
        entity.setKind(MemoryKind.PLACE.name());
        entity.setName("老家");
        entity.setContent(content);
        entity.setOrigin(MemoryOrigin.AUTO_EXTRACT.name());
        entity.setStatus(MemoryStatus.ACTIVE.name());
        entity.setHitCount(2);
        entity.setFirstSeenAt(LocalDateTime.now().minusDays(60));
        entity.setLastSeenAt(LocalDateTime.now().minusDays(1));
        return entity;
    }
}
