import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vitest/config'

/**
 * Vitest 配置。
 *
 * `@` 别名必须与 vite.config.ts / tsconfig.json 的 `paths` 保持一致（G-29），
 * 否则单测里 `@/xxx` 导入会在 node 环境下解析失败。
 * 测试以纯 TS 单元为主（权限矩阵 / 工具函数），不依赖 DOM，环境固定 node。
 */
export default defineConfig({
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
  },
})
