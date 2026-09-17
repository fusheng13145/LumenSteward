package com.lumensteward.clawbot.domain.service;

import com.lumensteward.clawbot.common.error.ErrorCode;
import com.lumensteward.clawbot.common.exception.BizException;
import com.lumensteward.clawbot.common.util.JsonUtils;
import com.lumensteward.clawbot.common.util.MaskUtils;
import com.lumensteward.clawbot.domain.model.PetProfileCommand;
import com.lumensteward.clawbot.domain.model.PetProfilePatch;
import com.lumensteward.clawbot.domain.model.PetProfileView;
import com.lumensteward.clawbot.infrastructure.persistence.entity.PetProfileEntity;
import com.lumensteward.clawbot.infrastructure.persistence.repository.PetProfileRepository;
import com.lumensteward.clawbot.infrastructure.persistence.service.AuditLogService;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * {@link PetProfileService} 实现（SRS FR-14）。
 *
 * <p><b>唯一约束双保险（架构 3.3 / AC-C8）：</b>
 * <ol>
 *   <li>应用层：写事务内 {@code existsLive} 查重（快速失败、友好提示）；</li>
 *   <li>数据库：{@code uk_openid_pet_name_live_marker} 复合唯一约束兜底（并发下最终一致）；</li>
 *   <li>分布式锁：Redisson {@code lock:pet:write:{openid}} 串行化同用户写操作。</li>
 * </ol>
 * 锁为增强项：Redisson 不可用时降级为"无锁 + DB 唯一约束兜底"，不阻断写入。
 */
@Service
public class PetProfileServiceImpl implements PetProfileService {

    private static final Logger log = LoggerFactory.getLogger(PetProfileServiceImpl.class);

    private static final Set<String> PET_TYPES = Set.of("猫", "狗", "其他");
    private static final Set<String> GENDERS = Set.of("公", "母", "未知");
    private static final int PET_NAME_MAX_LENGTH = 32;
    private static final long LOCK_WAIT_SECONDS = 3L;
    private static final String LOCK_PREFIX = "lock:pet:write:";

    private final PetProfileRepository repository;
    private final RedissonClient redissonClient;
    private final AuditLogService auditLogService;

    /**
     * 构造器注入（G-14）。
     *
     * @param repository      档案仓库
     * @param redissonClient  Redisson 客户端（可为 null，表示无锁环境）
     * @param auditLogService 审计日志服务
     */
    public PetProfileServiceImpl(PetProfileRepository repository, RedissonClient redissonClient,
                                 AuditLogService auditLogService) {
        this.repository = repository;
        this.redissonClient = redissonClient;
        this.auditLogService = auditLogService;
    }

    @Override
    public PetProfileView create(String openid, PetProfileCommand cmd) {
        requireOpenid(openid);
        if (cmd == null) {
            throw BizException.of(ErrorCode.PARAM_MISSING, "档案内容缺失");
        }
        validateName(cmd.petName());
        validateFields(cmd.petType(), cmd.gender(), cmd.birthday(), cmd.weightKg());

        RLock lock = acquireLock(openid);
        try {
            if (repository.existsLive(openid, cmd.petName())) {
                throw BizException.of(ErrorCode.PET_NAME_DUPLICATE);
            }
            PetProfileEntity entity = new PetProfileEntity();
            entity.setOpenid(openid);
            entity.setPetName(cmd.petName());
            entity.setPetType(cmd.petType());
            entity.setBreed(cmd.breed());
            entity.setGender(cmd.gender());
            entity.setBirthday(cmd.birthday());
            entity.setWeightKg(cmd.weightKg());
            entity.setPersonality(cmd.personality());
            entity.setNotes(cmd.notes());
            try {
                repository.insert(entity);
            } catch (DuplicateKeyException e) {
                // DB 唯一约束兜底（并发场景）
                throw BizException.of(ErrorCode.PET_NAME_DUPLICATE);
            }
            PetProfileView view = PetProfileView.from(entity);
            audit("CREATE", openid, null, JsonUtils.toJson(view));
            return view;
        } finally {
            releaseLock(lock);
        }
    }

    @Override
    public List<PetProfileView> listLive(String openid) {
        requireOpenid(openid);
        return repository.listLive(openid).stream().map(PetProfileView::from).toList();
    }

    @Override
    public Optional<PetProfileView> findLiveByName(String openid, String petName) {
        requireOpenid(openid);
        validateName(petName);
        return repository.findLiveByName(openid, petName).map(PetProfileView::from);
    }

    @Override
    public PetProfileView update(String openid, String petName, PetProfilePatch patch) {
        requireOpenid(openid);
        validateName(petName);
        if (patch == null) {
            throw BizException.of(ErrorCode.PARAM_MISSING, "更新内容缺失");
        }
        validateFields(patch.petType(), patch.gender(), patch.birthday(), patch.weightKg());

        RLock lock = acquireLock(openid);
        try {
            PetProfileEntity entity = repository.findLiveByName(openid, petName)
                    .orElseThrow(() -> BizException.of(ErrorCode.PET_NOT_FOUND));
            String before = JsonUtils.toJson(PetProfileView.from(entity));
            // 仅更新非空项（AC-C4 增量更新）
            if (patch.petType() != null) {
                entity.setPetType(patch.petType());
            }
            if (patch.breed() != null) {
                entity.setBreed(patch.breed());
            }
            if (patch.gender() != null) {
                entity.setGender(patch.gender());
            }
            if (patch.birthday() != null) {
                entity.setBirthday(patch.birthday());
            }
            if (patch.weightKg() != null) {
                entity.setWeightKg(patch.weightKg());
            }
            if (patch.personality() != null) {
                entity.setPersonality(patch.personality());
            }
            if (patch.notes() != null) {
                entity.setNotes(patch.notes());
            }
            repository.updateById(entity);
            PetProfileEntity refreshed = repository.findLiveByName(openid, petName).orElse(entity);
            audit("UPDATE", openid, before, JsonUtils.toJson(PetProfileView.from(refreshed)));
            return PetProfileView.from(refreshed);
        } finally {
            releaseLock(lock);
        }
    }

    @Override
    public void softDelete(String openid, String petName) {
        requireOpenid(openid);
        validateName(petName);
        RLock lock = acquireLock(openid);
        try {
            PetProfileEntity entity = repository.findLiveByName(openid, petName)
                    .orElseThrow(() -> BizException.of(ErrorCode.PET_NOT_FOUND));
            String before = JsonUtils.toJson(PetProfileView.from(entity));
            repository.softDeleteById(entity.getId());
            audit("DELETE", openid, before, null);
        } finally {
            releaseLock(lock);
        }
    }

    private void requireOpenid(String openid) {
        if (openid == null || openid.isBlank()) {
            throw BizException.of(ErrorCode.PARAM_MISSING, "用户标识缺失");
        }
    }

    private void validateName(String petName) {
        if (petName == null || petName.isBlank()) {
            throw BizException.of(ErrorCode.PARAM_MISSING, "宠物昵称必填");
        }
        if (petName.length() > PET_NAME_MAX_LENGTH) {
            throw BizException.of(ErrorCode.PET_FIELD_INVALID, "宠物昵称不得超过 " + PET_NAME_MAX_LENGTH + " 字符");
        }
    }

    private void validateFields(String petType, String gender, LocalDate birthday, BigDecimal weightKg) {
        if (petType != null && !PET_TYPES.contains(petType)) {
            throw BizException.of(ErrorCode.PET_FIELD_INVALID, "宠物类型取值非法");
        }
        if (gender != null && !GENDERS.contains(gender)) {
            throw BizException.of(ErrorCode.PET_FIELD_INVALID, "性别取值非法");
        }
        if (birthday != null && birthday.isAfter(LocalDate.now())) {
            throw BizException.of(ErrorCode.PET_BIRTHDAY_INVALID);
        }
        if (weightKg != null && weightKg.signum() <= 0) {
            throw BizException.of(ErrorCode.PET_FIELD_INVALID, "体重须为正数");
        }
    }

    private RLock acquireLock(String openid) {
        if (redissonClient == null) {
            return null;
        }
        try {
            RLock lock = redissonClient.getLock(LOCK_PREFIX + openid);
            if (lock.tryLock(LOCK_WAIT_SECONDS, TimeUnit.SECONDS)) {
                return lock;
            }
            log.warn("获取宠物写锁超时，降级为无锁执行（DB 唯一约束兜底）");
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (RuntimeException e) {
            log.warn("Redisson 不可用，降级为无锁执行: err={}", e.getMessage());
            return null;
        }
    }

    private void releaseLock(RLock lock) {
        if (lock == null) {
            return;
        }
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (RuntimeException e) {
            log.warn("释放宠物写锁失败: err={}", e.getMessage());
        }
    }

    private void audit(String action, String openid, String before, String after) {
        try {
            auditLogService.record(null, "PROFILE", action, MaskUtils.openid(openid), before, after,
                    "用户通过对话操作档案", null, 1);
        } catch (RuntimeException e) {
            log.warn("审计记录失败（不阻断主链路）: err={}", e.getMessage());
        }
    }
}
