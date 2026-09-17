package com.lumensteward.clawbot.auth;

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
import com.lumensteward.clawbot.interfaces.dto.auth.AuthInfoVO;
import com.lumensteward.clawbot.interfaces.dto.auth.ChangePasswordRequest;
import com.lumensteward.clawbot.interfaces.dto.auth.LoginRequest;
import com.lumensteward.clawbot.interfaces.dto.auth.LoginResponse;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 认证服务单测（SRS FR-15 / AC-E1~E4）。
 *
 * <p>纯单测：以 Mockito 替换 Mapper 与 Redis 依赖，BCrypt 与 JWT 使用真实实现（无外部依赖）。
 */
class AuthServiceLockTest {

    private static final String SECRET = "lumensteward-clawbot-test-secret-0123456789";
    private static final String RAW_PASSWORD = "P@ssw0rd-123";

    private final SysAdminUserMapper mapper = mock(SysAdminUserMapper.class);
    private final TokenBlacklistService blacklistService = mock(TokenBlacklistService.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    private SysAdminUserEntity admin;
    private JwtTokenProvider jwtTokenProvider;
    private AuthServiceImpl authService;

    @BeforeEach
    void setUp() {
        admin = new SysAdminUserEntity();
        admin.setId(1L);
        admin.setUsername("superadmin");
        admin.setPasswordHash(passwordEncoder.encode(RAW_PASSWORD));
        admin.setDisplayName("超级管理员");
        admin.setRole(AdminRole.SUPER_ADMIN.getCode());
        admin.setStatus(1);
        admin.setFailCount(0);

        // 阈值 5 次、锁定 15 分钟（与 SRS 一致）
        SecurityProperties properties = new SecurityProperties(SECRET, 12, 5, 15, 300);
        jwtTokenProvider = new JwtTokenProvider(properties);
        authService = new AuthServiceImpl(mapper, passwordEncoder, jwtTokenProvider,
                blacklistService, auditLogService, properties);

        when(mapper.selectOne(any())).thenAnswer(invocation -> admin);
    }

    @Test
    @DisplayName("AC-E1：正确口令登录成功，返回 JWT 且失败计数归零")
    void shouldLoginSuccessfullyAndResetFailCount() {
        admin.setFailCount(3);

        LoginResponse response = authService.login(new LoginRequest("superadmin", RAW_PASSWORD), "127.0.0.1");

        assertThat(response.token()).isNotBlank();
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.role()).isEqualTo("SUPER_ADMIN");
        assertThat(admin.getFailCount()).isZero();
        assertThat(admin.getLockedUntil()).isNull();
        assertThat(admin.getLastLoginAt()).isNotNull();
        assertThat(admin.getLastLoginIp()).isEqualTo("127.0.0.1");

        Claims claims = jwtTokenProvider.parse(response.token());
        assertThat(claims.getSubject()).isEqualTo("superadmin");
    }

    @Test
    @DisplayName("AC-E4：连续 5 次失败后累计锁定，第 6 次即使用正确口令也拒绝（20004）")
    void shouldLockAfterFiveConsecutiveFailures() {
        for (int i = 1; i <= 5; i++) {
            assertThatThrownBy(() -> authService.login(new LoginRequest("superadmin", "wrong"), "127.0.0.1"))
                    .isInstanceOf(BizException.class)
                    .extracting(e -> ((BizException) e).getErrorCode())
                    .isEqualTo(ErrorCode.BAD_CREDENTIALS);
        }
        assertThat(admin.getFailCount()).isEqualTo(5);
        assertThat(admin.getLockedUntil()).isNotNull();

        assertThatThrownBy(() -> authService.login(new LoginRequest("superadmin", RAW_PASSWORD), "127.0.0.1"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.ACCOUNT_LOCKED);
    }

    @Test
    @DisplayName("禁用账号登录返回 20006（HTTP 403 语义）")
    void shouldRejectDisabledAccount() {
        admin.setStatus(0);

        assertThatThrownBy(() -> authService.login(new LoginRequest("superadmin", RAW_PASSWORD), "127.0.0.1"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.ACCOUNT_DISABLED);
    }

    @Test
    @DisplayName("账号不存在与口令错误同码同文案（防账号枚举）")
    void shouldNotRevealAccountExistence() {
        when(mapper.selectOne(any())).thenAnswer(invocation -> null);

        assertThatThrownBy(() -> authService.login(new LoginRequest("ghost", "whatever"), "127.0.0.1"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.BAD_CREDENTIALS);
    }

    @Test
    @DisplayName("登录成败均写审计（含 IP）")
    void shouldAuditLoginAttempts() {
        authService.login(new LoginRequest("superadmin", RAW_PASSWORD), "10.0.0.9");
        verify(auditLogService, times(1))
                .record(eq(1L), eq("AUTH"), eq("LOGIN"), eq("superadmin"), any(), any(), any(),
                        eq("10.0.0.9"), eq(1));

        assertThatThrownBy(() -> authService.login(new LoginRequest("superadmin", "wrong"), "10.0.0.9"))
                .isInstanceOf(BizException.class);
        verify(auditLogService, times(1))
                .record(eq(1L), eq("AUTH"), eq("LOGIN"), eq("superadmin"), any(), any(), any(),
                        eq("10.0.0.9"), eq(0));
    }

    @Test
    @DisplayName("登出将 jti 写入黑名单（8.3：jwt:blacklist:{jti}）")
    void shouldBlacklistJtiOnLogout() {
        String token = jwtTokenProvider.issue(admin);
        String jti = jwtTokenProvider.parse(token).getId();

        authService.logout(jti);

        verify(blacklistService, times(1)).blacklist(eq(jti), eq(jwtTokenProvider.ttlSeconds()));
    }

    @Test
    @DisplayName("改密：原密码错误拒绝，正确则覆写哈希并审计")
    void shouldChangePassword() {
        assertThatThrownBy(() -> authService.changePassword("superadmin",
                new ChangePasswordRequest("wrong-old", "NewPass-1234"), "127.0.0.1"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);

        authService.changePassword("superadmin",
                new ChangePasswordRequest(RAW_PASSWORD, "NewPass-1234"), "127.0.0.1");

        assertThat(passwordEncoder.matches("NewPass-1234", admin.getPasswordHash())).isTrue();
        verify(mapper, times(1)).updateById(any(SysAdminUserEntity.class));
        verify(auditLogService, times(1))
                .record(eq(1L), eq("AUTH"), eq("PASSWORD_CHANGE"), eq("superadmin"),
                        any(), any(), any(), eq("127.0.0.1"), eq(1));
    }

    @Test
    @DisplayName("auth/info 返回与角色同源的权限码（AC-E9 前端菜单依赖）")
    void shouldReturnPermissionsByRole() {
        AuthInfoVO info = authService.info("superadmin");

        assertThat(info.username()).isEqualTo("superadmin");
        assertThat(info.role()).isEqualTo("SUPER_ADMIN");
        assertThat(info.permissions()).contains("config:write", "user:write", "audit:view");

        admin.setRole(AdminRole.AUDITOR.getCode());
        AuthInfoVO auditor = authService.info("superadmin");
        assertThat(auditor.permissions()).contains("audit:view").doesNotContain("config:write", "user:write");
    }

    @Test
    @DisplayName("登录校验走 BCrypt：错误口令不匹配、正确口令匹配")
    void shouldUseBcryptMatching() {
        assertThat(passwordEncoder.matches(RAW_PASSWORD, admin.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches("not-the-password", admin.getPasswordHash())).isFalse();
    }
}
