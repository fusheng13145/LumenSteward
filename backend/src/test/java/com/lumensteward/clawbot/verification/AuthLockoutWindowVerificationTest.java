package com.lumensteward.clawbot.verification;

import com.lumensteward.clawbot.application.auth.AuthServiceImpl;
import com.lumensteward.clawbot.common.enums.AdminRole;
import com.lumensteward.clawbot.common.error.ErrorCode;
import com.lumensteward.clawbot.common.exception.BizException;
import com.lumensteward.clawbot.infrastructure.cache.TokenBlacklistService;
import com.lumensteward.clawbot.infrastructure.config.properties.SecurityProperties;
import com.lumensteward.clawbot.infrastructure.persistence.entity.SysAdminUserEntity;
import com.lumensteward.clawbot.infrastructure.persistence.mapper.SysAdminUserMapper;
import com.lumensteward.clawbot.infrastructure.persistence.service.AuditLogService;
import com.lumensteward.clawbot.infrastructure.security.JwtTokenProvider;
import com.lumensteward.clawbot.interfaces.dto.auth.LoginRequest;
import com.lumensteward.clawbot.interfaces.dto.auth.LoginResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 独立验证：登录失败锁定窗口与到期解锁（SRS FR-15 / AC-E4）。
 *
 * <p>用 Mapper 替身（无 DB），BCrypt/JWT 真实实现。除"第 6 次正确口令仍被拒"外，
 * 额外验证"锁定到期后可正常登录"这一工程师测试未覆盖的恢复路径。
 */
class AuthLockoutWindowVerificationTest {

    private static final String SECRET = "qa-lockout-secret-0123456789-abcdefghij";
    private static final String RAW = "P@ss-verify-123";

    private final SysAdminUserMapper mapper = mock(SysAdminUserMapper.class);
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    private SysAdminUserEntity admin;
    private AuthServiceImpl authService;

    @BeforeEach
    void setUp() {
        admin = new SysAdminUserEntity();
        admin.setId(11L);
        admin.setUsername("superadmin");
        admin.setPasswordHash(encoder.encode(RAW));
        admin.setRole(AdminRole.SUPER_ADMIN.getCode());
        admin.setStatus(1);
        admin.setFailCount(0);

        SecurityProperties properties = new SecurityProperties(SECRET, 12, 5, 15, 300);
        authService = new AuthServiceImpl(mapper, encoder, new JwtTokenProvider(properties),
                mock(TokenBlacklistService.class), mock(AuditLogService.class), properties);
        when(mapper.selectOne(any())).thenAnswer(invocation -> admin);
    }

    @Test
    @DisplayName("AC-E4：连续 5 次失败 → 第 6 次用正确口令仍被拒（20004），且 fail_count/locked_until 正确写入")
    void sixthAttemptRejectedEvenWithCorrectPassword() {
        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> authService.login(new LoginRequest("superadmin", "wrong"), "1.1.1.1"))
                    .isInstanceOf(BizException.class)
                    .extracting(e -> ((BizException) e).getErrorCode())
                    .isEqualTo(ErrorCode.BAD_CREDENTIALS);
        }

        assertThat(admin.getFailCount()).isEqualTo(5);
        assertThat(admin.getLockedUntil()).isNotNull();
        assertThat(admin.getLockedUntil()).isAfter(LocalDateTime.now());
        assertThat(admin.getLockedUntil()).isBefore(LocalDateTime.now().plusMinutes(16));

        assertThatThrownBy(() -> authService.login(new LoginRequest("superadmin", RAW), "1.1.1.1"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.ACCOUNT_LOCKED);
    }

    @Test
    @DisplayName("AC-E4 恢复路径：锁定到期后正确口令可登录，失败计数归零")
    void loginSucceedsAfterLockExpires() {
        // 模拟已过锁定窗口
        admin.setFailCount(5);
        admin.setLockedUntil(LocalDateTime.now().minusMinutes(1));

        LoginResponse response = authService.login(new LoginRequest("superadmin", RAW), "1.1.1.1");

        assertThat(response.token()).isNotBlank();
        assertThat(admin.getFailCount()).isZero();
        assertThat(admin.getLockedUntil()).isNull();
    }

    @Test
    @DisplayName("防枚举：账号不存在与口令错误同为 20005")
    void accountEnumerationPrevented() {
        when(mapper.selectOne(any())).thenAnswer(invocation -> null);

        assertThatThrownBy(() -> authService.login(new LoginRequest("ghost", "x"), "1.1.1.1"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.BAD_CREDENTIALS);
    }
}
