package com.lumensteward.clawbot.pet;

import com.lumensteward.clawbot.common.error.ErrorCode;
import com.lumensteward.clawbot.common.exception.BizException;
import com.lumensteward.clawbot.domain.model.PetProfileCommand;
import com.lumensteward.clawbot.domain.service.PetProfileServiceImpl;
import com.lumensteward.clawbot.infrastructure.persistence.service.AuditLogService;
import com.lumensteward.clawbot.support.InMemoryPetProfileRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 宠物档案唯一约束测试（AC-C6/C7/C8，BR-03，架构 3.3）。
 *
 * <p>覆盖：应用层查重拦截；软删除后同名可重建；DB 唯一约束兜底（双保险）。
 */
class PetUniqueConstraintTest {

    private static final String OPENID = "openid-user-2";

    private final InMemoryPetProfileRepository repository = new InMemoryPetProfileRepository();
    private final PetProfileServiceImpl service =
            new PetProfileServiceImpl(repository, null, NOOP_AUDIT);

    @Test
    @DisplayName("⑥ 同用户名重复登记 → PET_NAME_DUPLICATE（应用层拦截）")
    void shouldRejectDuplicateName() {
        service.create(OPENID, command("豆豆"));
        assertThatThrownBy(() -> service.create(OPENID, command("豆豆")))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PET_NAME_DUPLICATE);
    }

    @Test
    @DisplayName("⑥ 软删除后可重建同名宠物（BR-18 软删重建）")
    void shouldAllowRebuildAfterSoftDelete() {
        service.create(OPENID, command("豆豆"));
        service.softDelete(OPENID, "豆豆");

        // 删除后允许重建同名
        service.create(OPENID, command("豆豆"));
        assertThat(service.findLiveByName(OPENID, "豆豆")).isPresent();
        assertThat(repository.liveCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("⑥ 应用层漏判时 DB 唯一约束兜底 → PET_NAME_DUPLICATE（AC-C8 双保险）")
    void shouldRelyOnDatabaseConstraint() {
        repository.setThrowDuplicateOnInsert(true);
        assertThatThrownBy(() -> service.create(OPENID, command("新宠物")))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PET_NAME_DUPLICATE);
    }

    private static PetProfileCommand command(String name) {
        return new PetProfileCommand(name, "狗", "柯基", "公", null, BigDecimal.valueOf(6), "活泼", null);
    }

    private static final AuditLogService NOOP_AUDIT =
            (adminId, regType, action, target, before, after, reason, ip, result) -> {
                // 测试：不落库
            };
}
