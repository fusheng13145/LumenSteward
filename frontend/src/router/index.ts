import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { hasPermission } from '@/config/permissions'
import { useAuthStore } from '@/stores/auth'

/**
 * 路由与全局守卫（9.3 / G-26）。
 *
 * meta 约定：每条路由声明 `{ title, requiresAuth, requiredPermission }`。
 * **要求**：meta 中声明的权限字段与守卫实际读取的字段同名同源（类型增强见 src/env.d.ts），
 * 否则权限会「静默失效」。后端仍独立鉴权（FR-15 验收准则②），前端守卫仅用于体验。
 */
const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/login/index.vue'),
    meta: { title: '登录', requiresAuth: false },
  },
  {
    path: '/',
    component: () => import('@/layouts/MainLayout.vue'),
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'dashboard',
        component: () => import('@/views/dashboard/index.vue'),
        meta: { title: '概览看板', requiresAuth: true, requiredPermission: 'dashboard:view' },
      },
      {
        path: 'users',
        name: 'users',
        component: () => import('@/views/placeholder/index.vue'),
        meta: { title: '用户管理', requiresAuth: true, requiredPermission: 'user:view' },
      },
      {
        path: 'sessions',
        name: 'sessions',
        component: () => import('@/views/placeholder/index.vue'),
        meta: { title: '会话监控', requiresAuth: true, requiredPermission: 'session:view' },
      },
      {
        path: 'tool-logs',
        name: 'tool-logs',
        component: () => import('@/views/placeholder/index.vue'),
        meta: { title: '工具调用日志', requiresAuth: true, requiredPermission: 'toolLog:view' },
      },
      {
        path: 'configs',
        name: 'configs',
        component: () => import('@/views/placeholder/index.vue'),
        meta: { title: '系统配置', requiresAuth: true, requiredPermission: 'config:view' },
      },
      {
        path: 'audit-logs',
        name: 'audit-logs',
        component: () => import('@/views/placeholder/index.vue'),
        meta: { title: '审计日志', requiresAuth: true, requiredPermission: 'audit:view' },
      },
    ],
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    redirect: '/dashboard',
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach((to) => {
  const auth = useAuthStore()
  const appTitle = import.meta.env.VITE_APP_TITLE || '衔光管家'
  document.title = to.meta.title ? `${to.meta.title} · ${appTitle}` : appTitle

  // 公开页（登录）直接放行
  if (to.meta.requiresAuth === false) {
    return true
  }

  // 未登录 → 跳登录并携带 redirect 回跳路径（G-24 同源约定）
  if (!auth.isAuthenticated) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }

  // 已登录但无权限 → 回退到看板（后端仍会独立鉴权）
  const required = to.meta.requiredPermission
  if (required && !hasPermission(auth.role, required)) {
    return { path: '/dashboard' }
  }

  return true
})

export default router
