package com.lumensteward.clawbot.application.auth;

import com.lumensteward.clawbot.interfaces.dto.auth.AuthInfoVO;
import com.lumensteward.clawbot.interfaces.dto.auth.ChangePasswordRequest;
import com.lumensteward.clawbot.interfaces.dto.auth.LoginRequest;
import com.lumensteward.clawbot.interfaces.dto.auth.LoginResponse;

/**
 * 认证服务（架构 5.4 / SRS FR-15）。
 *
 * <p>承载登录（BCrypt 校验 + 连续失败锁定 + 审计）、登出（jti 入黑名单）、当前用户信息与改密。
 */
public interface AuthService {

    /**
     * 登录。
     *
     * @param request 登录请求
     * @param ip      来源 IP（审计用）
     * @return 登录响应（含 JWT）
     */
    LoginResponse login(LoginRequest request, String ip);

    /**
     * 登出：将当前 Token 的 jti 写入黑名单（8.3）。
     *
     * @param jti JWT 唯一标识（可为空，容错）
     */
    void logout(String jti);

    /**
     * 当前登录用户信息与权限。
     *
     * @param username 登录名
     * @return 用户信息
     */
    AuthInfoVO info(String username);

    /**
     * 修改密码。
     *
     * @param username 登录名
     * @param request  改密请求
     * @param ip       来源 IP（审计用）
     */
    void changePassword(String username, ChangePasswordRequest request, String ip);
}
