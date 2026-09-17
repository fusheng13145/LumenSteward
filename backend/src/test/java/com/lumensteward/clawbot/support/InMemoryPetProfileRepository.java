package com.lumensteward.clawbot.support;

import com.lumensteward.clawbot.infrastructure.persistence.entity.PetProfileEntity;
import com.lumensteward.clawbot.infrastructure.persistence.repository.PetProfileRepository;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 内存版宠物档案仓库（单元测试支撑）。
 *
 * <p>模拟软删除语义（{@code deletedAt != null} 视为已删除）；可开关 {@code throwDuplicateOnInsert}
 * 以模拟数据库唯一约束冲突（验证双保险兜底）。
 */
public class InMemoryPetProfileRepository implements PetProfileRepository {

    private final Map<Long, PetProfileEntity> store = new LinkedHashMap<>();
    private final AtomicLong sequence = new AtomicLong();
    private boolean throwDuplicateOnInsert = false;

    /** 开关：insert 时是否模拟 DB 唯一约束冲突。 */
    public void setThrowDuplicateOnInsert(boolean value) {
        this.throwDuplicateOnInsert = value;
    }

    /** 当前存活记录数。 */
    public int liveCount() {
        return (int) store.values().stream().filter(this::isLive).count();
    }

    @Override
    public int insert(PetProfileEntity entity) {
        if (throwDuplicateOnInsert) {
            throw new DuplicateKeyException("uk_openid_pet_name_live_marker");
        }
        if (entity.getId() == null) {
            entity.setId(sequence.incrementAndGet());
        }
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        store.put(entity.getId(), entity);
        return 1;
    }

    @Override
    public List<PetProfileEntity> listLive(String openid) {
        List<PetProfileEntity> result = new ArrayList<>();
        for (PetProfileEntity entity : store.values()) {
            if (isLive(entity) && openid != null && openid.equals(entity.getOpenid())) {
                result.add(entity);
            }
        }
        return result;
    }

    @Override
    public Optional<PetProfileEntity> findLiveByName(String openid, String petName) {
        for (PetProfileEntity entity : store.values()) {
            if (isLive(entity) && openid != null && openid.equals(entity.getOpenid())
                    && petName != null && petName.equals(entity.getPetName())) {
                return Optional.of(entity);
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<PetProfileEntity> findLiveById(Long id) {
        PetProfileEntity entity = id == null ? null : store.get(id);
        if (entity != null && isLive(entity)) {
            return Optional.of(entity);
        }
        return Optional.empty();
    }

    @Override
    public int updateById(PetProfileEntity entity) {
        if (entity.getId() != null && store.containsKey(entity.getId())) {
            entity.setUpdatedAt(LocalDateTime.now());
            store.put(entity.getId(), entity);
            return 1;
        }
        return 0;
    }

    @Override
    public int softDeleteById(Long id) {
        PetProfileEntity entity = store.get(id);
        if (entity == null) {
            return 0;
        }
        entity.setDeletedAt(LocalDateTime.now());
        return 1;
    }

    @Override
    public boolean existsLive(String openid, String petName) {
        return findLiveByName(openid, petName).isPresent();
    }

    private boolean isLive(PetProfileEntity entity) {
        return entity.getDeletedAt() == null;
    }
}
