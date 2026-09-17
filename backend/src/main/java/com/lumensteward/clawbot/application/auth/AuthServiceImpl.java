package com.lumensteward.clawbot.application.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * {@link AuthService} 实现（SRS FR-15 / 架构 5.4）。
 *
 * <p><b>安全要点：</b>
 * <ul>
 *   <li>口令比对使用 BCrypt（{@code matches}），明文不落库、不落日志（BR-20）；</li>
 *   <li>「账号不存在」与「口令错误」统一返回 {@code 20005}，<b>不区分账号存在性</b>；</li>
 *   <li>连续失败达阈值即锁定 {@code lockMinutes} 分钟（{@code locked_until} 落库）；</li>
 *   <li>禁用账号返回 {@code 20006}（HTTP 403）；</li>
 *   <li>登录成败均写 {@code log_audit}（含来源 IP）。</li>
 * </ul>
 */
@Service
public class AuthServiceImpl implements AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthServiceImpl.class);

    /** 角色 → 权限码（对齐 SRS 3.3 权限矩阵与前端 permissions.ts，两端同源）。 */
    private static final List<String> SUPER_ADMIN_PERMISSIONS = List.of(
            "dashboard:view", "user:view", "user:write", "profile:write", "session:view",
            "toolLog:view", "config:view", "config:write", "audit:view", "doctor:view");
    private static final List<String> OPERATOR_PERMISSIONS = List.of(
            "dashboard:view", "user:view", "profile:write", "session:view", "toolLog:view", "doctor:view");
    private static final List<String> AUDITOR_PERMISSIONS = List.of(
            "dashboard:view", "user:view", "session:view", "toolLog:view", "audit:view", "doctor:view");

    private final SysAdminUserMapper sysAdminUserMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final TokenBlacklistService tokenBlacklistService;
    private final AuditLogService auditLogService;
    private final SecurityProperties securityProperties;

    /**
     * 构造器注入（G-14）。
     *
     * @param sysAdminUserMapper    管理员 Mapper
     * @param passwordEncoder       BCrypt 编码器
     * @param jwtTokenProvider      JWT 提供者
     * @param tokenBlacklistService 黑名单服务
     * @param auditLogService       审计服务
     * @param securityProperties    安全配置
     */
    public AuthServiceImpl(SysAdminUserMapper sysAdminUserMapper,
                           PasswordEncoder passwordEncoder,
                           JwtTokenProvider jwtTokenProvider,
                           TokenBlacklistService tokenBlacklistService,
                           AuditLogService auditLogService,
                           SecurityProperties securityProperties) {
        this.sysAdminUserMapper = sysAdminUserMapper;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
        this.tokenBlacklistService = tokenBlacklistService;
        this.auditLogService = auditLogService;
        this.securityProperties = securityProperties;
    }

    @Override
    public LoginResponse login(LoginRequest request, String ip) {
        String username = request.username();
        SysAdminUserEntity admin = findByUsername(username);

        // 账号不存在：与口令错误同码同文案，避免账号枚举
        if (admin == null) {
            audit(null, "LOGIN", username, ip, 0);
            throw BizException.of(ErrorCode.BAD_CREDENTIALS);
        }
        // 锁定校验（FR-15 2b）
        if (admin.getLockedUntil() != null && admin.getLockedUntil().isAfter(LocalDateTime.now())) {
            audit(admin.getId(), "LOGIN_LOCKED", username, ip, 0);
            throw BizException.of(ErrorCode.ACCOUNT_LOCKED);
        }
        // 禁用校验
        if (admin.getStatus() != null && admin.getStatus() == 0) {
            audit(admin.getId(), "LOGIN_DISABLED", username, ip, 0);
            throw BizException.of(ErrorCode.ACCOUNT_DISABLED);
        }
        // 口令比对
        if (!passwordEncoder.matches(request.password(), admin.getPasswordHash())) {
            registerFailure(admin, ip);
            throw BizException.of(ErrorCode.BAD_CREDENTIALS);
        }
        return onSuccess(admin, ip);
    }

    @Override
    public void logout(String jti) {
        if (jti == null || jti.isBlank()) {
            return;
        }
        tokenBlacklistService.blacklist(jti, jwtTokenProvider.ttlSeconds());
    }

    @Override
    public AuthInfoVO info(String username) {
        SysAdminUserEntity admin = findByUsername(username);
        if (admin == null) {
            throw BizException.of(ErrorCode.UNAUTHENTICATED);
        }
        return new AuthInfoVO(admin.getUsername(), admin.getRole(), admin.getDisplayName(),
                permissionsOf(admin.getRole()));
    }

    @Override
    public void changePassword(String username, ChangePasswordRequest request, String ip) {
        SysAdminUserEntity admin = findByUsername(username);
        if (admin == null) {
            throw BizException.of(ErrorCode.UNAUTHENTICATED);
        }
        if (!passwordEncoder.matches(request.oldPassword(), admin.getPasswordHash())) {
            audit(admin.getId(), "PASSWORD_CHANGE", username, ip, 0);
            throw BizException.of(ErrorCode.PARAM_INVALID, "原密码不正确");
        }
        admin.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        sysAdminUserMapper.updateById(admin);
        audit(admin.getId(), "PASSWORD_CHANGE", username, ip, 1);
    }

    private LoginResponse onSuccess(SysAdminUserEntity admin, String ip) {
        admin.setFailCount(0);
        admin.setLockedUntil(null);
        admin.setLastLoginAt(LocalDateTime.now());
        admin.setLastLoginIp(ip);
        sysAdminUserMapper.updateById(admin);

        String token = jwtTokenProvider.issue(admin);
        audit(admin.getId(), "LOGIN", admin.getUsername(), ip, 1);
        return new LoginResponse(token, "Bearer", jwtTokenProvider.ttlSeconds(),
                admin.getRole(), admin.getDisplayName());
    }

    private void registerFailure(SysAdminUserEntity admin, String ip) {
        int threshold = Math.max(1, securityProperties.lockThreshold());
        int current = admin.getFailCount() == null ? 0 : admin.getFailCount();
        int next = current + 1;
        admin.setFailCount(next);
        if (next >= threshold) {
            admin.setLockedUntil(LocalDateTime.now().plusMinutes(Math.max(1, securityProperties.lockMinutes())));
            log.warn("管理员[{}]连续失败 {} 次，账号锁定至 {}", admin.getUsername(), next, admin.getLockedUntil());
        }
        sysAdminUserMapper.updateById(admin);
        audit(admin.getId(), "LOGIN", admin.getUsername(), ip, 0);
    }

    private SysAdminUserEntity findByUsername(String username) {
        if (username == null || username.isBlank()) {
            return null;
        }
        return sysAdminUserMapper.selectOne(new LambdaQueryWrapper<SysAdminUserEntity>()
                .eq(SysAdminUserEntity::getUsername, username)
                .last("LIMIT 1"));
    }

    private void audit(Long adminId, String action, String target, String ip, int result) {
        try {
            auditLogService.record(adminId, "AUTH", action, target, null, null, null, ip, result);
        } catch (RuntimeException e) {
            log.warn("登录审计写失败（不阻断认证）: err={}", e.getMessage());
        }
    }

    /**
     * 角色 → 权限码列表（与前端 {@code src/config/permissions.ts} 同源）。
     *
     * @param roleCode 角色落库值
     * @return 权限码列表；角色非法时为空列表
     */
    public static List<String> permissionsOf(String roleCode) {
        AdminRole role = AdminRole.findByCode(roleCode).orElse(null);
        if (role == null) {
            return List.of();
        }
        return ROLE_PERMISSIONS.getOrDefault(role, List.of());
    }

    private static final Map<AdminRole, List<String>> ROLE_PERMISSIONS = Map.of(
            AdminRole.SUPER_ADMIN, SUPER_ADMIN_PERMISSIONS,
            AdminRole.OPERATOR, OPERATOR_PERMISSIONS,
            AdminRole.AUDITOR, AUDITOR_PERMISSIONS);
}
