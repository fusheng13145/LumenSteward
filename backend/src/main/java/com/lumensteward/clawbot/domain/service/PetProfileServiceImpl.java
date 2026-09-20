package com.lumensteward.clawbot.domain.service;

import com.lumensteward.clawbot.common.error.ErrorCode;
import com.lumensteward.clawbot.common.exception.BizException;
import com.lumensteward.clawbot.common.util.JsonUtils;
import com.lumensteward.clawbot.common.util.MaskUtils;
import com.fasterxml.jackson.core.type.TypeReference;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Map;
import java.util.Objects;
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
            PetProfileView beforeView = PetProfileView.from(entity);
            repository.softDeleteById(entity.getId());
            audit("DELETE", openid, JsonUtils.toJson(beforeView), null);
        } finally {
            releaseLock(lock);
        }
    }

    @Override
    public PetProfileView updateById(Long id, PetProfilePatch patch) {
        PetProfileEntity entity = repository.findLiveById(id)
                .orElseThrow(() -> BizException.of(ErrorCode.PET_NOT_FOUND));
        return update(entity.getOpenid(), entity.getPetName(), patch);
    }

    @Override
    public void softDeleteById(Long id) {
        PetProfileEntity entity = repository.findLiveById(id)
                .orElseThrow(() -> BizException.of(ErrorCode.PET_NOT_FOUND));
        softDelete(entity.getOpenid(), entity.getPetName());
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

    private void audit(String action, String openid, String beforeJson, String afterJson) {
        try {
            String beforeValue = toFieldLevelValue(true, beforeJson, afterJson);
            String afterValue = toFieldLevelValue(false, beforeJson, afterJson);
            auditLogService.record(null, "PROFILE", action, MaskUtils.openid(openid), beforeValue, afterValue,
                    "用户通过对话操作档案", null, 1);
        } catch (RuntimeException e) {
            log.warn("审计记录失败（不阻断主链路）: err={}", e.getMessage());
        }
    }

    /**
     * 将整对象前后值裁剪为「字段级」表示，供 A-4 档案变更留痕存储（与 PetProfileAuditTest 契约一致）。
     *
     * <ul>
     *   <li>CREATE（beforeJson=null）：before 侧为 null，after 侧为新建快照（剔除 openid）；</li>
     *   <li>DELETE（afterJson=null）：after 侧为 null，before 侧为删除前快照（剔除 openid）；</li>
     *   <li>UPDATE（两者皆非空）：仅保留发生变更的字段——before 侧取旧值、after 侧取新值；</li>
     *   <li>无实际变更：两侧均为空对象 {@code {}}。</li>
     * </ul>
     * 任何情况下均剔除 {@code openid} 键（隐私脱敏，BR-22）。
     *
     * @param beforeSide true 计算 before 侧，false 计算 after 侧
     * @param beforeJson 变更前整对象 JSON（可空）
     * @param afterJson  变更后整对象 JSON（可空）
     * @return 字段级 JSON 字符串（空对象为 {@code "{}"}，缺省侧为 {@code null}）
     */
    private String toFieldLevelValue(boolean beforeSide, String beforeJson, String afterJson) {
        Map<String, Object> beforeMap = normalize(parseJson(beforeJson));
        Map<String, Object> afterMap = normalize(parseJson(afterJson));

        if (beforeMap == null && afterMap == null) {
            return "{}";
        }
        if (beforeSide) {
            if (beforeMap == null) {
                return null;
            }
            if (afterMap == null) {
                return JsonUtils.toJson(beforeMap);
            }
            return JsonUtils.toJson(collectChanged(beforeMap, afterMap, true));
        } else {
            if (afterMap == null) {
                return null;
            }
            if (beforeMap == null) {
                return JsonUtils.toJson(afterMap);
            }
            return JsonUtils.toJson(collectChanged(beforeMap, afterMap, false));
        }
    }

    private static Map<String, Object> collectChanged(Map<String, Object> beforeMap,
                                                      Map<String, Object> afterMap, boolean beforeSide) {
        Map<String, Object> changed = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : afterMap.entrySet()) {
            if (!Objects.equals(beforeMap.get(e.getKey()), e.getValue())) {
                changed.put(e.getKey(), beforeSide ? beforeMap.get(e.getKey()) : e.getValue());
            }
        }
        for (String key : beforeMap.keySet()) {
            if (!afterMap.containsKey(key) && !changed.containsKey(key)) {
                changed.put(key, beforeSide ? beforeMap.get(key) : afterMap.get(key));
            }
        }
        return changed;
    }

    private static Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        return JsonUtils.fromJson(json, new TypeReference<Map<String, Object>>() {});
    }

    /** 档案业务字段白名单：JSON 字段名（camelCase）→ 列名（snake_case），对齐 {@code biz_pet_profile}。 */
    private static final Map<String, String> PROFILE_FIELD_ALIAS = createFieldAlias();

    private static Map<String, String> createFieldAlias() {
        Map<String, String> alias = new LinkedHashMap<>();
        alias.put("petName", "pet_name");
        alias.put("petType", "pet_type");
        alias.put("breed", "breed");
        alias.put("gender", "gender");
        alias.put("birthday", "birthday");
        alias.put("weightKg", "weight_kg");
        alias.put("personality", "personality");
        alias.put("notes", "notes");
        alias.put("photoMediaId", "photo_media_id");
        return alias;
    }

    /**
     * 规范化档案变更快照：仅保留业务字段，并将 JSON 字段名（camelCase）映射为列名（snake_case），
     * 剔除系统字段（id/createdAt/updatedAt）与 openid（隐私脱敏，BR-22）。
     *
     * <p>与前端 {@code views/profile/history.vue} 的 {@code FIELD_LABELS}（snake_case）保持同源契约。
     *
     * @param map 原始快照（JSON 反序列化结果，可空）
     * @return 规范化后的业务字段映射；入参为 null 时返回 null
     */
    private static Map<String, Object> normalize(Map<String, Object> map) {
        if (map == null) {
            return null;
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        for (Map.Entry<String, String> alias : PROFILE_FIELD_ALIAS.entrySet()) {
            if (map.containsKey(alias.getKey())) {
                normalized.put(alias.getValue(), map.get(alias.getKey()));
            }
        }
        return normalized;
    }
}
