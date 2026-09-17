import type { App, Directive, DirectiveBinding } from 'vue'
import { hasPermission, type PermissionCode } from '@/config/permissions'
import { useAuthStore } from '@/stores/auth'

/**
 * 权限指令（G-28）。
 *
 * <p>用法：{@code <button v-permission="'config:write'">…</button>}。
 * 无权限时从 DOM 移除元素；权限字符串统一取自 {@code config/permissions.ts}，禁止硬编码。
 *
 * <p><b>注意：</b>仅为体验优化，<b>不构成安全边界</b>——后端独立鉴权（FR-15 验收准则②）。
 */
const permission: Directive<HTMLElement, PermissionCode | PermissionCode[]> = {
  mounted(el: HTMLElement, binding: DirectiveBinding<PermissionCode | PermissionCode[]>) {
    const auth = useAuthStore()
    const required = binding.value
    const codes = Array.isArray(required) ? required : [required]
    const allowed = codes.some((code) => hasPermission(auth.role, code))
    if (!allowed) {
      // 移除而非隐藏：避免通过 DevTools 直接暴露入口
      el.parentNode?.removeChild(el)
    }
  },
}

/**
 * 注册全局指令。
 *
 * @param app Vue 应用
 */
export function registerDirectives(app: App): void {
  app.directive('permission', permission)
}

export default permission
