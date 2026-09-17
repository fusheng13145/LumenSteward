import { defineStore } from 'pinia'
import { STORAGE_KEYS } from '@/utils/constants'

/**
 * 界面偏好态（9.3：按关注点拆分 store）。
 *
 * <p>侧边栏折叠等纯 UI 偏好，持久化到 localStorage（键 {@code STORAGE_KEYS.UI_PREFERENCES}）。
 */
interface UiState {
  /** 侧边栏是否折叠 */
  sidebarCollapsed: boolean
}

function readCollapsed(): boolean {
  try {
    const raw = localStorage.getItem(STORAGE_KEYS.UI_PREFERENCES)
    if (!raw) {
      return false
    }
    const parsed = JSON.parse(raw) as { sidebarCollapsed?: boolean }
    return Boolean(parsed.sidebarCollapsed)
  } catch {
    return false
  }
}

export const useUiStore = defineStore('ui', {
  state: (): UiState => ({
    sidebarCollapsed: readCollapsed(),
  }),

  actions: {
    /** 切换侧边栏折叠态并持久化 */
    toggleSidebar(): void {
      this.sidebarCollapsed = !this.sidebarCollapsed
      this.persist()
    },

    /** 设置折叠态并持久化 */
    setSidebarCollapsed(value: boolean): void {
      this.sidebarCollapsed = value
      this.persist()
    },

    persist(): void {
      try {
        localStorage.setItem(
          STORAGE_KEYS.UI_PREFERENCES,
          JSON.stringify({ sidebarCollapsed: this.sidebarCollapsed }),
        )
      } catch {
        // 隐私模式下 localStorage 可能不可用：忽略，不影响功能
      }
    },
  },
})
