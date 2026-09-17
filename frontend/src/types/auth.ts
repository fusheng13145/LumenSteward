import type { AdminRole } from '@/utils/constants'

/** 登录请求（POST /api/auth/login） */
export interface LoginRequest {
  /** 用户名 */
  username: string
  /** 密码（明文仅存在于请求体，禁止落库/落日志） */
  password: string
}

/** 登录响应 */
export interface LoginResponse {
  /** JWT 令牌 */
  token: string
  /** 令牌类型，固定 Bearer */
  tokenType: string
  /** 有效期（秒） */
  expiresIn: number
  /** 角色 */
  role: AdminRole
  /** 展示名 */
  displayName: string
}

/** 当前用户信息（GET /api/auth/info） */
export interface AuthInfo {
  /** 用户名 */
  username: string
  /** 角色 */
  role: AdminRole
  /** 展示名 */
  displayName: string
  /** 权限码列表（与 config/permissions.ts 同源） */
  permissions: string[]
}

/** 修改密码请求（POST /api/auth/password） */
export interface ChangePasswordRequest {
  /** 原密码 */
  oldPassword: string
  /** 新密码 */
  newPassword: string
}
