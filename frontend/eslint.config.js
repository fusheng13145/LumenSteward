import js from '@eslint/js'
import pluginVue from 'eslint-plugin-vue'
import tseslint from 'typescript-eslint'

/**
 * ESLint 9 扁平配置（NFR-MA-09）。
 * 统一 TS + Vue 规则；配合 .prettierrc 做格式化。
 */
export default [
  { ignores: ['dist/**', 'node_modules/**', 'coverage/**', '*.d.ts'] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  ...pluginVue.configs['flat/recommended'],
  {
    files: ['**/*.vue'],
    languageOptions: {
      parserOptions: {
        // 让 <script setup lang="ts"> 使用 TS 解析器
        parser: tseslint.parser,
      },
    },
  },
  {
    rules: {
      // 本项目页面组件以单一名词命名（login、dashboard），放行多词约束
      'vue/multi-word-component-names': 'off',
    },
  },
]
