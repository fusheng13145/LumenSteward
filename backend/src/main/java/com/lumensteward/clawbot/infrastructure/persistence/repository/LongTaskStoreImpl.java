package com.lumensteward.clawbot.infrastructure.persistence.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lumensteward.clawbot.common.enums.LongTaskStatus;
import com.lumensteward.clawbot.domain.task.LongTaskRecord;
import com.lumensteward.clawbot.domain.task.LongTaskStore;
import com.lumensteward.clawbot.infrastructure.persistence.entity.LongTaskEntity;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.LongTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * {@link LongTaskStore} 的 MyBatis 实现（迭代 4 W15，照 {@code MemoryStoreImpl} 模式）。
 *
 * <p>状态迁移委托 {@code LongTaskMapper} 的条件 UPDATE；查询侧实体 → {@link LongTaskRecord}
 * 领域快照映射，openid 等敏感字段不在此处脱敏（出参装配统一走 MaskingAssembler，BR-21）。
 */
@Repository
public class LongTaskStoreImpl implements LongTaskStore {

    private static final Logger log = LoggerFactory.getLogger(LongTaskStoreImpl.class);

    private final LongTaskMapper mapper;

    /** 构造器注入（G-14）。 */
    public LongTaskStoreImpl(LongTaskMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public long insert(LongTaskRecord task) {
        LongTaskEntity entity = new LongTaskEntity();
        entity.setTaskType(task.taskType());
        entity.setOpenid(task.openid());
        entity.setStatus(LongTaskStatus.PENDING.name());
        entity.setPayloadJson(task.payloadJson());
        entity.setTraceId(task.traceId());
        entity.setAttempts(0);
        mapper.insert(entity);
        return entity.getId();
    }

    @Override
    public Optional<LongTaskRecord> find(long id) {
        return Optional.ofNullable(mapper.selectById(id)).map(LongTaskStoreImpl::toRecord);
    }

    @Override
    public boolean claim(long id) {
        return mapper.claimPending(id) == 1;
    }

    @Override
    public boolean completeSuccess(long id, String resultJson) {
        return mapper.completeSuccess(id, resultJson) == 1;
    }

    @Override
    public boolean completeFailure(long id, String errorType) {
        return mapper.completeFailure(id, errorType) == 1;
    }

    @Override
    public boolean markDelivered(long id) {
        return mapper.markDelivered(id) == 1;
    }

    @Override
    public List<LongTaskRecord> findPending(int limit) {
        return find(LongTaskStatus.PENDING, limit);
    }

    @Override
    public List<LongTaskRecord> findSucceededUndelivered(int limit) {
        LambdaQueryWrapper<LongTaskEntity> wrapper = new LambdaQueryWrapper<LongTaskEntity>()
                .eq(LongTaskEntity::getStatus, LongTaskStatus.SUCCEEDED.name())
                .isNull(LongTaskEntity::getDeliveredAt)
                .orderByAsc(LongTaskEntity::getId)
                .last("limit " + capped(limit));
        return mapper.selectList(wrapper).stream().map(LongTaskStoreImpl::toRecord).toList();
    }

    @Override
    public void requeueRunning() {
        int requeued = mapper.requeueRunning();
        if (requeued > 0) {
            log.warn("长任务续跑：{} 个 RUNNING 残局已重置回 PENDING", requeued);
        }
    }

    private List<LongTaskRecord> find(LongTaskStatus status, int limit) {
        LambdaQueryWrapper<LongTaskEntity> wrapper = new LambdaQueryWrapper<LongTaskEntity>()
                .eq(LongTaskEntity::getStatus, status.name())
                .orderByAsc(LongTaskEntity::getId)
                .last("limit " + capped(limit));
        return mapper.selectList(wrapper).stream().map(LongTaskStoreImpl::toRecord).toList();
    }

    private static int capped(int limit) {
        return limit <= 0 ? 100 : Math.min(limit, 500);
    }

    private static LongTaskRecord toRecord(LongTaskEntity e) {
        return new LongTaskRecord(e.getId(), e.getTaskType(), e.getOpenid(),
                LongTaskStatus.valueOf(e.getStatus()), e.getPayloadJson(), e.getResultJson(),
                e.getErrorType(), e.getAttempts() == null ? 0 : e.getAttempts(),
                e.getDeliveredAt() != null, e.getTraceId());
    }
}
