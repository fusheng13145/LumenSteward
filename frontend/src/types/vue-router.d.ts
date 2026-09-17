import 'vue-router'

/**
 * 路由 meta 类型增强（G-26：meta 声明的权限字段与守卫实际读取的字段同名同源）。
 *
 * 注意：本文件必须含顶层 import，使 `declare module` 成为「模块增强」而非「模块替换」——
 * 若置于无 import 的全局脚本（如 env.d.ts）中，会覆盖 vue-router 的真实导出，导致
 * `createRouter` / `useRouter` 等「无导出成员」错误。
 */
declare module 'vue-router' {
  interface RouteMeta {
    /** 页面标题，用于 document.title */
    title?: string
    /** 是否需要登录态 */
    requiresAuth?: boolean
    /** 访问所需权限码（与 config/permissions.ts 同源） */
    requiredPermission?: string
  }
}
