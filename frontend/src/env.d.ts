/// <reference types="vite/client" />

/** 环境变量类型（与 .env.example 契约一一对应，G-30） */
interface ImportMetaEnv {
  /** 应用标题 */
  readonly VITE_APP_TITLE: string
  /** 后端 API 基础路径 */
  readonly VITE_API_BASE_URL: string
  /** 本地开发代理目标（仅 dev） */
  readonly VITE_API_PROXY_TARGET?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}

/**
 * .vue 单文件组件类型声明。
 * 使 `tsc --noEmit`（不含 vue-tsc 的 SFC 能力时）也能解析 `import App from './App.vue'`；
 * `vue-tsc` 会以真实 SFC 类型优先覆盖本声明。
 */
declare module '*.vue' {
  import type { DefineComponent } from 'vue'

  const component: DefineComponent<Record<string, never>, Record<string, never>, unknown>
  export default component
}
