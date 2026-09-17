import { request } from '@/utils/api'
import type { AuthInfo, ChangePasswordRequest, LoginRequest, LoginResponse } from '@/types/auth'

/**
 * 认证域 API（9.3：一域一文件，方法命名与 REST 语义对齐）。
 *
 * 基础路径 `/api` 由 axios 单例统一提供，故此处 url 相对其书写。
 */
export const authApi = {
  /** 登录，返回 JWT（POST /api/auth/login） */
  login(payload: LoginRequest): Promise<LoginResponse> {
    return request<LoginResponse>({ url: '/auth/login', method: 'post', data: payload })
  },

  /** 登出，令牌入黑名单（POST /api/auth/logout） */
  logout(): Promise<null> {
    return request<null>({ url: '/auth/logout', method: 'post' })
  },

  /** 当前用户与权限（GET /api/auth/info） */
  info(): Promise<AuthInfo> {
    return request<AuthInfo>({ url: '/auth/info', method: 'get' })
  },

  /** 修改密码（POST /api/auth/password） */
  changePassword(payload: ChangePasswordRequest): Promise<null> {
    return request<null>({ url: '/auth/password', method: 'post', data: payload })
  },
}
