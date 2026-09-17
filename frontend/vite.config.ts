import { fileURLToPath, URL } from 'node:url'
import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

/**
 * Vite 配置。
 *
 * 关键：`@` 别名必须与 tsconfig.json 的 `paths` 双端同步声明（G-29）——仅配一端会导致
 * 「编辑器报错但构建通过」或反向不一致的隐蔽缺陷。
 */
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const proxyTarget = env.VITE_API_PROXY_TARGET || 'http://localhost:8080'

  return {
    plugins: [vue()],
    resolve: {
      alias: {
        // 与 tsconfig.json 的 "paths": { "@/*": ["src/*"] } 保持一致
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
    server: {
      host: '0.0.0.0',
      port: 5173,
      proxy: {
        // 本地开发把 /api 代理到后端，规避跨域
        '/api': {
          target: proxyTarget,
          changeOrigin: true,
        },
      },
    },
    build: {
      outDir: 'dist',
      sourcemap: false,
      chunkSizeWarningLimit: 1500,
    },
  }
})
