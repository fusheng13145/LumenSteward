import { defineStore } from 'pinia'
import { configApi } from '@/services/config.api'
import type { ConfigVO } from '@/types/config'

/**
 * 系统配置态（9.3：按关注点拆分 store）。
 *
 * <p>承载配置列表与运行模式（Mock/Real）标识，供配置页展示；本次仅 SUPER_ADMIN 可加载。
 */
interface ConfigState {
  /** 配置项列表 */
  items: ConfigVO[]
  /** 是否加载中 */
  loading: boolean
}

export const useConfigStore = defineStore('config', {
  state: (): ConfigState => ({
    items: [],
    loading: false,
  }),

  getters: {
    /** LLM 运行模式（mock/real），未加载时为空串 */
    llmProvider(state): string {
      return state.items.find((item) => item.configKey === 'runtime.llm.provider')?.configValue ?? ''
    },
    /** 微信通道是否 Mock 模式 */
    wechatMockEnabled(state): boolean {
      return state.items.find((item) => item.configKey === 'runtime.wx.mock.enabled')?.configValue === 'true'
    },
    /** 落库配置项（剔除 runtime.* 运行模式项） */
    persistedItems(state): ConfigVO[] {
      return state.items.filter((item) => !item.configKey.startsWith('runtime.'))
    },
  },

  actions: {
    /** 拉取配置列表（含运行模式项） */
    async load(): Promise<ConfigVO[]> {
      this.loading = true
      try {
        this.items = await configApi.list()
        return this.items
      } finally {
        this.loading = false
      }
    },

    /** 清空（登出时调用，避免越权数据残留） */
    reset(): void {
      this.items = []
      this.loading = false
    },
  },
})
