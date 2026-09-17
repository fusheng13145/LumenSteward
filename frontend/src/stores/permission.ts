import { defineStore } from 'pinia'
import { hasMenuPermission, hasPermission, type PermissionCode } from '@/config/permissions'
import { useAuthStore } from '@/stores/auth'
import type { AdminRole } from '@/utils/constants'

/**
 * 权限态（9.3：按关注点拆分 store）。
 *
 * <p>把「角色 → 权限」的判定集中收敛：模板与业务统一调用 {@code permission.can(...)}，
 * 避免权限字符串散落在组件里（G-27）。后端仍独立鉴权（FR-15 验收准则②）。
 */
export const usePermissionStore = defineStore('permission', {
  getters: {
    /** 当前角色（未登录为空串） */
    role(): AdminRole | '' {
      return useAuthStore().role
    },
    /** 全部权限码（来自后端 /api/auth/info） */
    permissions(): string[] {
      return useAuthStore().permissions
    },
  },

  actions: {
    /**
     * 是否具备某权限码。
     *
     * @param permission 权限码
     * @returns 是否具备
     */
    can(permission: PermissionCode | string): boolean {
      return hasPermission(this.role, permission)
    },

    /**
     * 是否可访问某菜单。
     *
     * @param menuKey 菜单 key（与路由 name 对齐）
     * @returns 是否可访问
     */
    canMenu(menuKey: string): boolean {
      return hasMenuPermission(this.role, menuKey)
    },
  },
})
