package com.lumensteward.clawbot.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lumensteward.clawbot.common.enums.CommitmentStatus;
import com.lumensteward.clawbot.domain.reminder.CommitmentRecord;
import com.lumensteward.clawbot.domain.reminder.CommitmentStore;
import com.lumensteward.clawbot.infrastructure.persistence.entity.CommitmentEntity;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.CommitmentMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * {@link CommitmentStore} 的 MyBatis 实现（迭代 4 W7/W8，照 {@code LongTaskStoreImpl} 模式）。
 *
 * <p>登记侧截断 content 到 255（列宽）；查询侧实体 → {@link CommitmentRecord} 领域快照，
 * openid 脱敏由出参装配承担（BR-21）。
 */
@Repository
public class CommitmentStoreImpl implements CommitmentStore {

    private static final Logger log = LoggerFactory.getLogger(CommitmentStoreImpl.class);

    /** content 列宽。 */
    private static final int CONTENT_MAX = 255;

    private final CommitmentMapper mapper;

    /** 构造器注入（G-14）。 */
    public CommitmentStoreImpl(CommitmentMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public long register(String openid, String content, LocalDateTime dueAt,
                         Long sourceSessionId, String sourceTraceId) {
        if (openid == null || openid.isBlank() || content == null || content.isBlank() || dueAt == null) {
            return -1L;
        }
        CommitmentEntity entity = new CommitmentEntity();
        entity.setOpenid(openid);
        entity.setContent(clip(content, CONTENT_MAX));
        entity.setDueAt(dueAt);
        entity.setStatus(CommitmentStatus.PENDING.name());
        entity.setSourceSessionId(sourceSessionId);
        entity.setSourceTraceId(sourceTraceId);
        mapper.insert(entity);
        return entity.getId();
    }

    @Override
    public Optional<CommitmentRecord> find(long id) {
        return Optional.ofNullable(mapper.selectById(id)).map(CommitmentStoreImpl::toRecord);
    }

    @Override
    public boolean markDelivered(long id) {
        return mapper.markDelivered(id) == 1;
    }

    @Override
    public List<CommitmentRecord> findDue(LocalDateTime cutoff, int limit) {
        LambdaQueryWrapper<CommitmentEntity> wrapper = new LambdaQueryWrapper<CommitmentEntity>()
                .eq(CommitmentEntity::getStatus, CommitmentStatus.PENDING.name())
                .le(CommitmentEntity::getDueAt, cutoff)
                .orderByAsc(CommitmentEntity::getDueAt)
                .last("limit " + capped(limit));
        return mapper.selectList(wrapper).stream().map(CommitmentStoreImpl::toRecord).toList();
    }

    @Override
    public List<CommitmentRecord> findByOpenid(String openid, int limit) {
        if (openid == null || openid.isBlank()) {
            return List.of();
        }
        LambdaQueryWrapper<CommitmentEntity> wrapper = new LambdaQueryWrapper<CommitmentEntity>()
                .eq(CommitmentEntity::getOpenid, openid)
                .orderByAsc(CommitmentEntity::getDueAt)
                .last("limit " + capped(limit));
        return mapper.selectList(wrapper).stream().map(CommitmentStoreImpl::toRecord).toList();
    }

    private static int capped(int limit) {
        return limit <= 0 ? 50 : Math.min(limit, 200);
    }

    private static String clip(String value, int max) {
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    private static CommitmentRecord toRecord(CommitmentEntity e) {
        return new CommitmentRecord(e.getId(), e.getOpenid(), e.getContent(), e.getDueAt(),
                e.getStatus() == null ? CommitmentStatus.PENDING
                        : CommitmentStatus.valueOf(e.getStatus()),
                e.getSourceSessionId(), e.getSourceTraceId());
    }
}
