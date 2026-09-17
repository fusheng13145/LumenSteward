package com.lumensteward.clawbot.pet;

import com.lumensteward.clawbot.common.error.ErrorCode;
import com.lumensteward.clawbot.common.exception.BizException;
import com.lumensteward.clawbot.domain.model.PetProfileCommand;
import com.lumensteward.clawbot.domain.model.PetProfilePatch;
import com.lumensteward.clawbot.domain.model.PetProfileView;
import com.lumensteward.clawbot.domain.service.PetProfileServiceImpl;
import com.lumensteward.clawbot.infrastructure.persistence.service.AuditLogService;
import com.lumensteward.clawbot.support.InMemoryPetProfileRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 宠物档案 CRUD 测试（AC-C1~C9，SRS FR-14）。
 *
 * <p>覆盖：多宠物互不干扰、增量更新（只改生日）、软删除、字段校验（昵称必填、生日 ≤ 今日）。
 */
class PetProfileCrudTest {

    private static final String OPENID = "openid-user-1";

    private final InMemoryPetProfileRepository repository = new InMemoryPetProfileRepository();
    private final PetProfileServiceImpl service =
            new PetProfileServiceImpl(repository, null, NOOP_AUDIT);

    @Test
    @DisplayName("⑥ 登记 ≥3 只宠物且互不干扰（AC-C2，FR-14 验收②）")
    void shouldCreateMultiplePets() {
        service.create(OPENID, command("豆豆", "狗", "柯基"));
        service.create(OPENID, command("咪咪", "猫", "英短"));
        service.create(OPENID, command("旺财", "狗", "中华田园犬"));

        List<PetProfileView> live = service.listLive(OPENID);
        assertThat(live).hasSize(3);
        assertThat(service.findLiveByName(OPENID, "咪咪")).isPresent();
    }

    @Test
    @DisplayName("⑥ 增量更新：只改生日，其余字段不变（AC-C4）")
    void shouldPatchOnlyProvidedField() {
        service.create(OPENID, command("豆豆", "狗", "柯基"));
        LocalDate birthday = LocalDate.of(2021, 5, 1);

        PetProfileView updated = service.update(OPENID, "豆豆", new PetProfilePatch(
                null, null, null, birthday, null, null, null));

        assertThat(updated.birthday()).isEqualTo(birthday);
        assertThat(updated.petType()).isEqualTo("狗");
        assertThat(updated.breed()).isEqualTo("柯基");
    }

    @Test
    @DisplayName("⑥ 软删除后查不到，但记录保留（AC-C5，BR-18）")
    void shouldSoftDelete() {
        service.create(OPENID, command("豆豆", "狗", "柯基"));
        service.create(OPENID, command("咪咪", "猫", "英短"));

        service.softDelete(OPENID, "豆豆");

        assertThat(service.findLiveByName(OPENID, "豆豆")).isEmpty();
        assertThat(service.listLive(OPENID)).hasSize(1);
    }

    @Test
    @DisplayName("⑥ 昵称缺失 → 参数错误（BR-02）")
    void shouldRejectMissingName() {
        assertThatThrownBy(() -> service.create(OPENID, command("", "狗", "柯基")))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_MISSING);
    }

    @Test
    @DisplayName("⑥ 生日晚于今日 → PET_BIRTHDAY_INVALID（BR-02）")
    void shouldRejectFutureBirthday() {
        PetProfileCommand command = new PetProfileCommand("未来", "猫", null, null,
                LocalDate.now().plusDays(1), BigDecimal.ONE, null, null);
        assertThatThrownBy(() -> service.create(OPENID, command))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PET_BIRTHDAY_INVALID);
    }

    @Test
    @DisplayName("⑥ 更新不存在的宠物 → PET_NOT_FOUND（AC-C9）")
    void shouldFailUpdateMissingPet() {
        assertThatThrownBy(() -> service.update(OPENID, "不存在", new PetProfilePatch(
                "猫", null, null, null, null, null, null)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PET_NOT_FOUND);
    }

    private static PetProfileCommand command(String name, String type, String breed) {
        return new PetProfileCommand(name, type, breed, "未知", null, BigDecimal.valueOf(5), "黏人", null);
    }

    private static final AuditLogService NOOP_AUDIT =
            (adminId, regType, action, target, before, after, reason, ip, result) -> {
                // 测试：不落库
            };
}
