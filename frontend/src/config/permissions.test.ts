import { describe, expect, it } from 'vitest'
import {
  hasMenuPermission,
  hasPermission,
  MENU_PERMISSIONS,
  PERMISSIONS,
  ROLE_PERMISSIONS,
} from '@/config/permissions'
import type { AdminRole } from '@/utils/constants'

/**
 * 权限矩阵回归（三角色口径与后端 @PreAuthorize 同源；此前整仓无任何前端单测，
 * CI「前端 · 测试」环节空跑——本文件使该门禁环节首次真实生效）。
 */
describe('权限矩阵（config/permissions.ts）', () => {
  it('SUPER_ADMIN 持有全部权限码（写入口全部可见）', () => {
    for (const code of Object.values(PERMISSIONS)) {
      expect(hasPermission('SUPER_ADMIN', code), code).toBe(true)
    }
  })

  it('写入口按 SRS 3.3 矩阵收敛：OPERATOR/AUDITOR 不得触碰独占项', () => {
    const adminOnly = [
      PERMISSIONS.CONFIG_WRITE,
      PERMISSIONS.CONFIG_VIEW,
      PERMISSIONS.USER_WRITE,
      PERMISSIONS.MEMORY_DELETE,
      PERMISSIONS.COMPLIANCE_EXPORT,
    ]
    for (const code of adminOnly) {
      expect(hasPermission('OPERATOR', code), `OPERATOR×${code}`).toBe(false)
      expect(hasPermission('AUDITOR', code), `AUDITOR×${code}`).toBe(false)
    }
    // 回放/档案写/任务放弃：OPERATOR 可用、AUDITOR 只读不可
    expect(hasPermission('OPERATOR', PERMISSIONS.TOOL_LOG_REPLAY)).toBe(true)
    expect(hasPermission('OPERATOR', PERMISSIONS.PROFILE_WRITE)).toBe(true)
    expect(hasPermission('AUDITOR', PERMISSIONS.TOOL_LOG_REPLAY)).toBe(false)
    expect(hasPermission('AUDITOR', PERMISSIONS.PROFILE_WRITE)).toBe(false)
  })

  it('三角色均可读看板类只读权限（监控/状态库/成本口径同源）', () => {
    const readOnly = [
      PERMISSIONS.MONITOR_VIEW,
      PERMISSIONS.MEMORY_VIEW,
      PERMISSIONS.COST_VIEW,
      PERMISSIONS.DASHBOARD_VIEW,
    ]
    const roles: AdminRole[] = ['SUPER_ADMIN', 'OPERATOR', 'AUDITOR']
    for (const role of roles) {
      for (const code of readOnly) {
        expect(hasPermission(role, code), `${role}×${code}`).toBe(true)
      }
    }
  })

  it('未登录（空角色）一律拒绝；未知权限码一律拒绝', () => {
    expect(hasPermission('', PERMISSIONS.DASHBOARD_VIEW)).toBe(false)
    expect(hasPermission('SUPER_ADMIN', 'not:a:code')).toBe(false)
  })

  it('菜单权限：成本看板三角色可见，合规导出仅 SUPER_ADMIN，未知菜单拒绝', () => {
    expect(hasMenuPermission('SUPER_ADMIN', 'compliance-export')).toBe(true)
    expect(hasMenuPermission('OPERATOR', 'compliance-export')).toBe(false)
    expect(hasMenuPermission('AUDITOR', 'compliance-export')).toBe(false)
    expect(hasMenuPermission('OPERATOR', 'cost')).toBe(true)
    expect(hasMenuPermission('AUDITOR', 'cost')).toBe(true)
    expect(hasMenuPermission('SUPER_ADMIN', 'no-such-menu')).toBe(false)
  })

  it('矩阵一致性守护：SUPER_ADMIN 权限覆盖 OPERATOR 与 AUDITOR 的并集', () => {
    const superSet = new Set(ROLE_PERMISSIONS.SUPER_ADMIN)
    for (const code of [...ROLE_PERMISSIONS.OPERATOR, ...ROLE_PERMISSIONS.AUDITOR]) {
      expect(superSet.has(code), `SUPER_ADMIN 缺失 ${code}`).toBe(true)
    }
    // 菜单映射引用的权限码必须真实存在（防手滑写出未注册权限）
    for (const code of Object.values(MENU_PERMISSIONS)) {
      expect(Object.values(PERMISSIONS).includes(code), `菜单引用了未注册权限 ${code}`).toBe(true)
    }
  })
})
