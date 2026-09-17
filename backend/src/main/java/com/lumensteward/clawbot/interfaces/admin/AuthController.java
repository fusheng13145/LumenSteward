package com.lumensteward.clawbot.interfaces.admin;

import com.lumensteward.clawbot.application.auth.AuthService;
import com.lumensteward.clawbot.common.api.ApiResponse;
import com.lumensteward.clawbot.infrastructure.security.AuthPrincipal;
import com.lumensteward.clawbot.interfaces.dto.auth.AuthInfoVO;
import com.lumensteward.clawbot.interfaces.dto.auth.ChangePasswordRequest;
import com.lumensteward.clawbot.interfaces.dto.auth.LoginRequest;
import com.lumensteward.clawbot.interfaces.dto.auth.LoginResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证控制器（架构 4.3 / SRS FR-15）。
 *
 * <p>{@code /api/auth/login} 为公开端点；其余需认证。登录成败均写审计（含 IP）。
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "认证", description = "登录 / 登出 / 当前用户 / 改密（JWT + RBAC）")
public class AuthController {

    private final AuthService authService;

    /**
     * 构造器注入（G-14）。
     *
     * @param authService 认证服务
     */
    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 登录并签发 JWT。
     *
     * @param request 登录请求
     * @return 登录响应
     */
    @PostMapping("/login")
    @Operation(summary = "登录", description = "BCrypt 校验 + 连续失败锁定；成功返回 JWT")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                            HttpServletRequest httpRequest) {
        return ApiResponse.success(authService.login(request, ClientIp.of(httpRequest)));
    }

    /**
     * 登出：当前 Token 的 jti 入黑名单。
     *
     * @param principal 当前主体
     * @return 空响应
     */
    @PostMapping("/logout")
    @Operation(summary = "登出", description = "将当前 Token 的 jti 写入 jwt:blacklist:{jti}")
    public ApiResponse<Void> logout(@AuthenticationPrincipal AuthPrincipal principal) {
        if (principal != null) {
            authService.logout(principal.jti());
        }
        return ApiResponse.success();
    }

    /**
     * 当前用户与权限。
     *
     * @param principal 当前主体
     * @return 用户信息
     */
    @GetMapping("/info")
    @Operation(summary = "当前用户", description = "返回登录名、角色与权限码列表")
    public ApiResponse<AuthInfoVO> info(@AuthenticationPrincipal AuthPrincipal principal) {
        return ApiResponse.success(authService.info(principal.username()));
    }

    /**
     * 修改密码。
     *
     * @param principal 当前主体
     * @param request   改密请求
     * @return 空响应
     */
    @PostMapping("/password")
    @Operation(summary = "修改密码")
    public ApiResponse<Void> changePassword(@AuthenticationPrincipal AuthPrincipal principal,
                                            @Valid @RequestBody ChangePasswordRequest request,
                                            HttpServletRequest httpRequest) {
        authService.changePassword(principal.username(), request, ClientIp.of(httpRequest));
        return ApiResponse.success();
    }
}
