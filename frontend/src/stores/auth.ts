import { defineStore } from 'pinia'
import { authApi } from '@/services/auth.api'
import type { AuthInfo, LoginRequest, LoginResponse } from '@/types/auth'
import { STORAGE_KEYS, type AdminRole } from '@/utils/constants'

/** 身份态（9.3：按关注点拆分 store，禁止把不相关状态堆入单一 store） */
interface AuthState {
  /** JWT 令牌 */
  token: string
  /** 用户名 */
  username: string
  /** 展示名 */
  displayName: string
  /** 角色；未登录为空串 */
  role: AdminRole | ''
  /** 权限码列表 */
  permissions: string[]
}

export const useAuthStore = defineStore('auth', {
  state: (): AuthState => ({
    token: localStorage.getItem(STORAGE_KEYS.TOKEN) ?? '',
    username: '',
    displayName: '',
    role: '',
    permissions: [],
  }),

  getters: {
    /** 是否已登录（以令牌是否存在为准） */
    isAuthenticated: (state): boolean => state.token.length > 0,
  },

  actions: {
    /**
     * 登录并持久化令牌。
     *
     * @param payload 登录请求
     * @returns 登录响应
     */
    async login(payload: LoginRequest): Promise<LoginResponse> {
      const result = await authApi.login(payload)
      this.token = result.token
      this.role = result.role
      this.displayName = result.displayName
      localStorage.setItem(STORAGE_KEYS.TOKEN, result.token)
      return result
    },

    /**
     * 拉取当前用户信息与权限。
     *
     * @returns 用户信息
     */
    async fetchInfo(): Promise<AuthInfo> {
      const info = await authApi.info()
      this.username = info.username
      this.displayName = info.displayName
      this.role = info.role
      this.permissions = info.permissions
      return info
    },

    /** 登出：先请求后端（令牌入黑名单），无论成败都清理本地态 */
    async logout(): Promise<void> {
      try {
        await authApi.logout()
      } finally {
        this.reset()
      }
    },

    /** 清空身份态与本地令牌 */
    reset(): void {
      this.token = ''
      this.username = ''
      this.displayName = ''
      this.role = ''
      this.permissions = []
      localStorage.removeItem(STORAGE_KEYS.TOKEN)
    },
  },
})
