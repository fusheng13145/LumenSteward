import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { hasPermission } from '@/config/permissions'
import { useAuthStore } from '@/stores/auth'

/**
 * 路由与全局守卫（9.3 / G-26）。
 *
 * meta 约定：每条路由声明 `{ title, requiresAuth, requiredPermission }`。
 * **要求**：meta 中声明的权限字段与守卫实际读取的字段同名同源（类型增强见 src/types/vue-router.d.ts），
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
        component: () => import('@/views/user/index.vue'),
        meta: { title: '用户管理', requiresAuth: true, requiredPermission: 'user:view' },
      },
      {
        path: 'users/:id',
        name: 'user-detail',
        component: () => import('@/views/user-detail/index.vue'),
        meta: { title: '用户详情', requiresAuth: true, requiredPermission: 'user:view' },
      },
      {
        path: 'sessions',
        name: 'sessions',
        component: () => import('@/views/session/index.vue'),
        meta: { title: '会话监控', requiresAuth: true, requiredPermission: 'session:view' },
      },
      {
        path: 'sessions/:id',
        name: 'session-detail',
        component: () => import('@/views/session-detail/index.vue'),
        meta: { title: '会话详情', requiresAuth: true, requiredPermission: 'session:view' },
      },
      {
        path: 'tool-logs',
        name: 'tool-logs',
        component: () => import('@/views/tool-log/index.vue'),
        meta: { title: '工具调用日志', requiresAuth: true, requiredPermission: 'toolLog:view' },
      },
      {
        path: 'tool-logs/replay',
        name: 'tool-log-replay',
        component: () => import('@/views/tool-log/replay.vue'),
        meta: { title: '工具调用回放', requiresAuth: true, requiredPermission: 'toolLog:replay' },
      },
      {
        path: 'configs',
        name: 'configs',
        component: () => import('@/views/config/index.vue'),
        meta: { title: '系统配置', requiresAuth: true, requiredPermission: 'config:view' },
      },
      {
        path: 'audit-logs',
        name: 'audit-logs',
        component: () => import('@/views/audit-log/index.vue'),
        meta: { title: '审计日志', requiresAuth: true, requiredPermission: 'audit:view' },
      },
      {
        path: 'monitor',
        name: 'monitor',
        component: () => import('@/views/monitor/index.vue'),
        meta: { title: '监控看板', requiresAuth: true, requiredPermission: 'monitor:view' },
      },
      {
        path: 'monitor/console',
        name: 'monitor-console',
        component: () => import('@/views/monitor/console.vue'),
        meta: { title: '实时观测台', requiresAuth: true, requiredPermission: 'monitor:view' },
      },
      {
        path: 'profiles/history',
        name: 'profiles-history',
        component: () => import('@/views/profile/history.vue'),
        meta: { title: '档案变更留痕', requiresAuth: true, requiredPermission: 'profile:history' },
      },
      {
        path: 'memories',
        name: 'memories',
        component: () => import('@/views/memory/index.vue'),
        meta: { title: '个人状态库', requiresAuth: true, requiredPermission: 'memory:view' },
      },
      {
        path: 'compliance/export',
        name: 'compliance-export',
        component: () => import('@/views/compliance/export.vue'),
        meta: { title: '合规报告导出', requiresAuth: true, requiredPermission: 'compliance:export' },
      },
    ],
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/views/not-found/index.vue'),
    meta: { title: '页面不存在', requiresAuth: true },
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach(async (to) => {
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

  // 硬刷新时仅令牌从 localStorage 恢复，角色为空串；须先取回身份再判权限，
  // 否则下面的权限检查对任何受限路由都失败并回退 /dashboard——而 /dashboard
  // 自身同样失败——形成守卫无限重定向（既有缺陷，深链/刷新即触发）。
  // 令牌失效时此处失败 → 回落登录页（axios 拦截器的 401 处理为兜底）。
  if (!auth.role) {
    try {
      await auth.fetchInfo()
    } catch {
      return { path: '/login', query: { redirect: to.fullPath } }
    }
  }

  // 已登录但无权限 → 回退到看板（后端仍会独立鉴权）
  const required = to.meta.requiredPermission
  if (required && !hasPermission(auth.role, required)) {
    return { path: '/dashboard' }
  }

  return true
})

export default router
