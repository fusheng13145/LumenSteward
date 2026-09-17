<script setup lang="ts">
import { useRouter } from 'vue-router'

/** 404 页面（T05）：承载未匹配路由，替代此前的静默重定向。 */
const router = useRouter()
</script>

<template>
  <section class="not-found">
    <h2>404 · 页面不存在</h2>
    <p class="hint">您访问的页面不存在或已被移除。</p>
    <el-button type="primary" @click="router.replace('/dashboard')">返回概览看板</el-button>
  </section>
</template>

<style scoped>
.not-found {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 60vh;
  gap: 12px;
}

.hint {
  color: #909399;
}
</style>
