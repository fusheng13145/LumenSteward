package com.lumensteward.clawbot.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lumensteward.clawbot.common.enums.MemoryKind;
import com.lumensteward.clawbot.common.enums.MemoryOrigin;
import com.lumensteward.clawbot.common.enums.MemoryStatus;
import com.lumensteward.clawbot.domain.memory.MemoryFact;
import com.lumensteward.clawbot.domain.memory.MemoryStore;
import com.lumensteward.clawbot.domain.memory.MemoryWrite;
import com.lumensteward.clawbot.infrastructure.persistence.entity.MemoryItemEntity;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.MemoryItemMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * {@link MemoryStore} 的 MyBatis-Plus 实现（迭代 4 W6）。
 *
 * <p><b>查重为首要防线</b>：先按 (openid, kind, name, ACTIVE) 查活记录，再决定新增 / 覆盖 / 强化；
 * 数据库唯一约束 {@code uk_openid_kind_name_live_marker} 为并发下的兜底
 * （命中冲突即视为「他人已写入同键」，转为强化，不产生重复事实）。
 *
 * <p>覆盖写顺序刻意是「旧行先转 SUPERSEDED → 再插入新行」：旧行一旦让出 live_marker，
 * 唯一槽位即空出，避免插入撞约束。
 *
 * <p>BR-07：所有查询与删除均以 openid 为强制条件，本类不提供跨用户入口。
 */
@Repository
public class MemoryStoreImpl implements MemoryStore {

    private static final Logger log = LoggerFactory.getLogger(MemoryStoreImpl.class);

    /** 召回条数硬上限（防单用户记忆无限挤占上下文）。 */
    public static final int MAX_RECALL_LIMIT = 20;

    private static final int NAME_MAX = 64;
    private static final int CONTENT_MAX = 512;

    private final MemoryItemMapper memoryItemMapper;

    /**
     * 构造器注入（G-14）。
     *
     * @param memoryItemMapper 状态库 Mapper
     */
    public MemoryStoreImpl(MemoryItemMapper memoryItemMapper) {
        this.memoryItemMapper = memoryItemMapper;
    }

    @Override
    public WriteOutcome upsert(MemoryWrite write) {
        if (write == null || !write.valid()) {
            return null;
        }
        String name = clip(write.name(), NAME_MAX);
        String content = clip(write.content(), CONTENT_MAX);
        MemoryItemEntity active = findActive(write.openid(), write.kind().name(), name);
        if (active == null) {
            return insertNew(write, name, content);
        }
        if (content.equals(active.getContent())) {
            memoryItemMapper.reinforce(active.getId());
            return WriteOutcome.REINFORCED;
        }
        return supersedeAndInsert(active, write, name, content);
    }

    @Override
    public List<MemoryFact> recallActive(String openid, int limit) {
        if (openid == null || openid.isBlank()) {
            return List.of();
        }
        int capped = Math.min(Math.max(limit, 1), MAX_RECALL_LIMIT);
        List<MemoryItemEntity> rows = memoryItemMapper.selectList(
                new LambdaQueryWrapper<MemoryItemEntity>()
                        .eq(MemoryItemEntity::getOpenid, openid)
                        .eq(MemoryItemEntity::getStatus, MemoryStatus.ACTIVE.name())
                        .orderByDesc(MemoryItemEntity::getLastSeenAt)
                        .orderByDesc(MemoryItemEntity::getId)
                        .last("limit " + capped));
        return rows.stream().map(MemoryStoreImpl::toFact).toList();
    }

    @Override
    public int deleteAllByOpenid(String openid) {
        if (openid == null || openid.isBlank()) {
            return 0;
        }
        return memoryItemMapper.deleteAllByOpenid(openid);
    }

    @Override
    public int purgeSupersededBefore(LocalDateTime cutoff) {
        if (cutoff == null) {
            return 0;
        }
        return memoryItemMapper.deleteSupersededBefore(cutoff);
    }

    private WriteOutcome insertNew(MemoryWrite write, String name, String content) {
        MemoryItemEntity entity = toInsertEntity(write, name, content);
        try {
            memoryItemMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            MemoryItemEntity racer = findActive(write.openid(), write.kind().name(), name);
            if (racer == null) {
                throw e;
            }
            log.debug("状态库唯一约束命中，转为强化 name={}", name);
            memoryItemMapper.reinforce(racer.getId());
            return WriteOutcome.REINFORCED;
        }
        return WriteOutcome.CREATED;
    }

    private WriteOutcome supersedeAndInsert(MemoryItemEntity active, MemoryWrite write,
                                           String name, String content) {
        active.setStatus(MemoryStatus.SUPERSEDED.name());
        memoryItemMapper.updateById(active);

        MemoryItemEntity entity = toInsertEntity(write, name, content);
        entity.setSupersedesId(active.getId());
        entity.setFirstSeenAt(active.getFirstSeenAt());
        try {
            memoryItemMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            // 旧行已让出槽位 yet 仍冲突 ⇒ 并发写者已建立同键事实，放弃本次覆盖即可
            log.debug("并发覆盖冲突，丢弃本次写入 name={}", name);
            return WriteOutcome.REINFORCED;
        }
        return WriteOutcome.SUPERSEDED;
    }

    private MemoryItemEntity findActive(String openid, String kind, String name) {
        return memoryItemMapper.selectOne(new LambdaQueryWrapper<MemoryItemEntity>()
                .eq(MemoryItemEntity::getOpenid, openid)
                .eq(MemoryItemEntity::getKind, kind)
                .eq(MemoryItemEntity::getName, name)
                .eq(MemoryItemEntity::getStatus, MemoryStatus.ACTIVE.name())
                .last("limit 1"));
    }

    private static MemoryItemEntity toInsertEntity(MemoryWrite write, String name, String content) {
        LocalDateTime now = LocalDateTime.now();
        MemoryItemEntity entity = new MemoryItemEntity();
        entity.setOpenid(write.openid());
        entity.setKind(write.kind().name());
        entity.setName(name);
        entity.setContent(content);
        entity.setOrigin(write.origin().name());
        entity.setExtractor(clip(write.extractor(), 32));
        entity.setConfidence(clampConfidence(write.confidence()));
        entity.setSourceSessionId(write.sourceSessionId());
        entity.setSourceTraceId(clip(write.sourceTraceId(), 64));
        entity.setStatus(MemoryStatus.ACTIVE.name());
        entity.setHitCount(1);
        entity.setFirstSeenAt(now);
        entity.setLastSeenAt(now);
        return entity;
    }

    private static MemoryFact toFact(MemoryItemEntity e) {
        return new MemoryFact(e.getId(), e.getOpenid(), kindOf(e.getKind()),
                e.getName(), e.getContent(), originOf(e.getOrigin()),
                e.getExtractor(), e.getConfidence(), statusOf(e.getStatus()),
                e.getSupersedesId(), e.getSourceSessionId(), e.getSourceTraceId(),
                e.getHitCount() == null ? 0 : e.getHitCount(), e.getFirstSeenAt(), e.getLastSeenAt());
    }

    /** 落库值 → 枚举；无法识别时回落 FACT（读模型宁粗不崩，召回不得因脏值中断）。 */
    private static MemoryKind kindOf(String raw) {
        MemoryKind kind = MemoryKind.parse(raw);
        return kind == null ? MemoryKind.FACT : kind;
    }

    private static MemoryOrigin originOf(String raw) {
        return parseEnum(MemoryOrigin.class, raw, MemoryOrigin.AUTO_EXTRACT);
    }

    private static MemoryStatus statusOf(String raw) {
        return parseEnum(MemoryStatus.class, raw, MemoryStatus.ACTIVE);
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, E fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, raw.trim());
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static String clip(String raw, int max) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private static BigDecimal clampConfidence(BigDecimal raw) {
        if (raw == null || raw.signum() < 0) {
            return null;
        }
        return raw.compareTo(BigDecimal.ONE) > 0 ? BigDecimal.ONE : raw;
    }
}
