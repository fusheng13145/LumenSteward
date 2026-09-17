package com.lumensteward.clawbot.verification;

import com.lumensteward.clawbot.common.error.ErrorCode;
import com.lumensteward.clawbot.common.exception.BizException;
import com.lumensteward.clawbot.domain.model.PetProfileCommand;
import com.lumensteward.clawbot.domain.service.PetProfileServiceImpl;
import com.lumensteward.clawbot.infrastructure.persistence.service.AuditLogService;
import com.lumensteward.clawbot.support.InMemoryPetProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * 独立验证：宠物档案唯一性、软删除重建、字段校验、跨用户隔离
 * （SRS FR-14 / BR-03 / BR-07 / AC-C3 / AC-C6 / AC-C7 / AC-C9）。
 *
 * <p>用内存仓库替代 MySQL（本机无 DB 凭据）；Redisson 传 null（无锁降级路径），
 * 审计服务以 Mockito 替身。QA 独立编写。
 */
class PetProfileUniquenessVerificationTest {

    private static final String USER_A = "openid-user-A-1234567890";
    private static final String USER_B = "openid-user-B-0987654321";

    private InMemoryPetProfileRepository repository;
    private PetProfileServiceImpl service;

    private static PetProfileCommand cat(String name, LocalDate birthday) {
        return new PetProfileCommand(name, "猫", "柯基", "公", birthday, null, null, null);
    }

    @BeforeEach
    void setUp() {
        repository = new InMemoryPetProfileRepository();
        service = new PetProfileServiceImpl(repository, null, mock(AuditLogService.class));
    }

    @Test
    @DisplayName("AC-C3：同名重复登记被拦截（应用层 dedup），不产生重复行")
    void duplicateNameBlocked() {
        service.create(USER_A, cat("豆豆", LocalDate.of(2020, 5, 1)));

        assertThatThrownBy(() -> service.create(USER_A, cat("豆豆", null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PET_NAME_DUPLICATE));
        assertThat(repository.liveCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-C6：软删除后可重建同名宠物（唯一约束含 deleted_at 语义）")
    void recreateAfterSoftDelete() {
        service.create(USER_A, cat("豆豆", null));
        service.softDelete(USER_A, "豆豆");
        assertThat(repository.liveCount()).as("软删除后无存活记录").isZero();

        service.create(USER_A, cat("豆豆", null));

        assertThat(repository.liveCount()).isEqualTo(1);
        assertThat(service.findLiveByName(USER_A, "豆豆")).isPresent();
    }

    @Test
    @DisplayName("AC-C7：生日晚于今日被拒（字段值域校验）")
    void futureBirthdayRejected() {
        LocalDate tomorrow = LocalDate.now().plusDays(1);

        assertThatThrownBy(() -> service.create(USER_A, cat("豆豆", tomorrow)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PET_BIRTHDAY_INVALID));
        assertThat(repository.liveCount()).isZero();
    }

    @Test
    @DisplayName("AC-C9：跨用户不可见（BR-07 按 openid 隔离）")
    void crossUserIsolation() {
        service.create(USER_A, cat("咪咪", null));

        assertThat(service.findLiveByName(USER_B, "咪咪")).isEmpty();
        assertThat(service.listLive(USER_B)).isEmpty();
        assertThat(service.listLive(USER_A)).hasSize(1);
    }

    @Test
    @DisplayName("昵称缺失被拒（BR-08 必填）")
    void blankNameRejected() {
        assertThatThrownBy(() -> service.create(USER_A, cat("  ", null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PARAM_MISSING));
    }

    @Test
    @DisplayName("DB 唯一约束兜底：insert 抛 DuplicateKey 时转为业务错误（双保险）")
    void dbUniqueConstraintFallback() {
        repository.setThrowDuplicateOnInsert(true);

        assertThatThrownBy(() -> service.create(USER_A, cat("豆豆", null)))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ErrorCode.PET_NAME_DUPLICATE));
    }
}
