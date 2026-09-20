<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { hasMenuPermission } from '@/config/permissions'
import { useAuthStore } from '@/stores/auth'
import { useConfigStore } from '@/stores/config'
import { ROLE_LABELS } from '@/utils/constants'

/**
 * 后台主布局（T05）：顶部栏 + 侧边菜单 + 内容区。
 *
 * 菜单按 `hasMenuPermission` 过滤（G-27），权限字符串不在此硬编码。
 * 首次进入时拉取 `/api/auth/info` 以恢复角色与权限（刷新页面后权限守卫依赖它）。
 */
const auth = useAuthStore()
const configStore = useConfigStore()
const router = useRouter()

interface MenuItem {
  /** 与路由 name / MENU_PERMISSIONS key 对齐 */
  key: string
  label: string
  to: string
}

const allMenus: MenuItem[] = [
  { key: 'dashboard', label: '概览看板', to: '/dashboard' },
  { key: 'users', label: '用户管理', to: '/users' },
  { key: 'sessions', label: '会话监控', to: '/sessions' },
  { key: 'tool-logs', label: '工具调用日志', to: '/tool-logs' },
  { key: 'configs', label: '系统配置', to: '/configs' },
  { key: 'audit-logs', label: '审计日志', to: '/audit-logs' },
  { key: 'monitor', label: '实时观测台', to: '/monitor/console' },
  { key: 'profiles', label: '档案变更留痕', to: '/profiles/history' },
]

const visibleMenus = computed(() => allMenus.filter((m) => hasMenuPermission(auth.role, m.key)))

const roleLabel = computed(() => (auth.role ? ROLE_LABELS[auth.role] : '未登录'))

const appTitle = computed(() => import.meta.env.VITE_APP_TITLE || '衔光管家')

async function handleLogout(): Promise<void> {
  await auth.logout()
  configStore.reset()
  await router.replace({ path: '/login' })
}

onMounted(async () => {
  if (auth.isAuthenticated && !auth.username) {
    try {
      await auth.fetchInfo()
    } catch {
      // 拉取失败（如令牌过期）由 axios 拦截器统一处理跳登录
    }
  }
})
</script>

<template>
  <div class="layout">
    <header class="layout__header">
      <div class="layout__brand">
        {{ appTitle }}
      </div>
      <div class="layout__user">
        <span>{{ auth.displayName || auth.username || '未登录' }}</span>
        <em>({{ roleLabel }})</em>
        <button
          type="button"
          @click="handleLogout"
        >
          退出登录
        </button>
      </div>
    </header>

    <div class="layout__body">
      <aside class="layout__aside">
        <nav>
          <router-link
            v-for="menu in visibleMenus"
            :key="menu.key"
            class="layout__nav-item"
            :to="menu.to"
          >
            {{ menu.label }}
          </router-link>
        </nav>
      </aside>

      <main class="layout__main">
        <router-view />
      </main>
    </div>
  </div>
</template>

<style scoped>
.layout {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
}

.layout__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 56px;
  padding: 0 24px;
  color: #fff;
  background: #1f2d3d;
}

.layout__brand {
  font-size: 18px;
  font-weight: 600;
}

.layout__user {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
}

.layout__user button {
  padding: 4px 12px;
  color: #fff;
  cursor: pointer;
  background: #3a4a5e;
  border: none;
  border-radius: 4px;
}

.layout__body {
  display: flex;
  flex: 1;
}

.layout__aside {
  width: 200px;
  padding: 12px 0;
  background: #f5f7fa;
  border-right: 1px solid #e4e7ed;
}

.layout__nav-item {
  display: block;
  padding: 10px 24px;
  color: #303133;
  text-decoration: none;
}

.layout__nav-item.router-link-active {
  color: #409eff;
  background: #ecf5ff;
}

.layout__main {
  flex: 1;
  padding: 24px;
}
</style>
