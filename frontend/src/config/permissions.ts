import type { AdminRole } from '@/utils/constants'

/**
 * 权限集中配置（G-27 / 9.3 / SRS 3.3）。
 *
 * 集中声明三张映射：① 权限码（域:动作）② 角色→权限 ③ 菜单→权限，并导出纯函数。
 * 业务代码与模板中**禁止**出现权限字符串硬编码，一律调用 hasPermission / hasMenuPermission。
 */

/** ① 权限码：`域:动作` 语义 */
export const PERMISSIONS = {
  /** 概览看板查看 */
  DASHBOARD_VIEW: 'dashboard:view',
  /** 用户列表/详情查看 */
  USER_VIEW: 'user:view',
  /** 用户启用/禁用（SUPER_ADMIN 独占） */
  USER_WRITE: 'user:write',
  /** 宠物档案写入（OPERATOR+） */
  PROFILE_WRITE: 'profile:write',
  /** 会话与消息查看 */
  SESSION_VIEW: 'session:view',
  /** 工具调用日志查看 */
  TOOL_LOG_VIEW: 'toolLog:view',
  /** 工具调用回放（A-2；SUPER_ADMIN/OPERATOR，AUDITOR 不可） */
  TOOL_LOG_REPLAY: 'toolLog:replay',
  /** 系统配置查看（SUPER_ADMIN 独占） */
  CONFIG_VIEW: 'config:view',
  /** 系统配置写入（SUPER_ADMIN 独占） */
  CONFIG_WRITE: 'config:write',
  /** 审计日志查看 */
  AUDIT_VIEW: 'audit:view',
  /** 系统自检报告查看 */
  DOCTOR_VIEW: 'doctor:view',
  /** 实时观测台查看（FR-08 / 迭代 3 Wave 2 T3） */
  MONITOR_VIEW: 'monitor:view',
  /** 档案变更留痕查看（A-4 / 迭代 3 Wave 2 T7） */
  PROFILE_HISTORY: 'profile:history',
} as const

/** 权限码字面量联合类型 */
export type PermissionCode = (typeof PERMISSIONS)[keyof typeof PERMISSIONS]

const ALL_PERMISSIONS = Object.values(PERMISSIONS) as PermissionCode[]

/** ② 角色 → 权限（对齐 SRS 3.3 权限矩阵） */
export const ROLE_PERMISSIONS: Record<AdminRole, PermissionCode[]> = {
  // 系统管理员：全量读 + 配置写 + 用户禁用
  SUPER_ADMIN: ALL_PERMISSIONS,
  // 运营管理员：用户/档案/会话/日志/看板读 + 档案写；不可改配置
  OPERATOR: [
    PERMISSIONS.DASHBOARD_VIEW,
    PERMISSIONS.USER_VIEW,
    PERMISSIONS.PROFILE_WRITE,
    PERMISSIONS.SESSION_VIEW,
    PERMISSIONS.TOOL_LOG_VIEW,
    PERMISSIONS.TOOL_LOG_REPLAY,
    PERMISSIONS.MONITOR_VIEW,
    PERMISSIONS.PROFILE_HISTORY,
    PERMISSIONS.DOCTOR_VIEW,
  ],
  // 审计员：日志与统计只读；不可修改任何业务数据
  AUDITOR: [
    PERMISSIONS.DASHBOARD_VIEW,
    PERMISSIONS.USER_VIEW,
    PERMISSIONS.SESSION_VIEW,
    PERMISSIONS.TOOL_LOG_VIEW,
    PERMISSIONS.AUDIT_VIEW,
    PERMISSIONS.MONITOR_VIEW,
    PERMISSIONS.DOCTOR_VIEW,
  ],
}

/** ③ 菜单 → 所需权限（菜单 key 与路由 name 对齐） */
export const MENU_PERMISSIONS: Record<string, PermissionCode> = {
  dashboard: PERMISSIONS.DASHBOARD_VIEW,
  users: PERMISSIONS.USER_VIEW,
  sessions: PERMISSIONS.SESSION_VIEW,
  'tool-logs': PERMISSIONS.TOOL_LOG_VIEW,
  configs: PERMISSIONS.CONFIG_VIEW,
  'audit-logs': PERMISSIONS.AUDIT_VIEW,
  monitor: PERMISSIONS.MONITOR_VIEW,
  profiles: PERMISSIONS.PROFILE_HISTORY,
}

/**
 * 判断角色是否具备某权限。
 *
 * @param role 角色；未登录传空串
 * @param permission 权限码
 * @returns 是否具备
 */
export function hasPermission(role: AdminRole | '', permission: PermissionCode | string): boolean {
  if (!role) {
    return false
  }
  const granted = ROLE_PERMISSIONS[role]
  if (!granted) {
    return false
  }
  return granted.includes(permission as PermissionCode)
}

/**
 * 判断角色是否可访问某菜单。
 *
 * @param role 角色；未登录传空串
 * @param menuKey 菜单 key
 * @returns 是否可访问
 */
export function hasMenuPermission(role: AdminRole | '', menuKey: string): boolean {
  const required = MENU_PERMISSIONS[menuKey]
  if (!required) {
    return false
  }
  return hasPermission(role, required)
}
