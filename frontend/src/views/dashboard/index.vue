<script setup lang="ts">
import { computed } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { ROLE_LABELS } from '@/utils/constants'

/** 概览看板（T01 占位实现；统计图表/接口对接留待 T05，ECharts 本期预留未启用）。 */
const auth = useAuthStore()
const roleLabel = computed(() => (auth.role ? ROLE_LABELS[auth.role] : '—'))
</script>

<template>
  <section>
    <h2>概览看板</h2>
    <p>欢迎，{{ auth.displayName || auth.username || '管理员' }}（{{ roleLabel }}）。</p>
    <p class="hint">本页为 T01 工程骨架占位，业务统计与接口对接将在后续批次实现。</p>
  </section>
</template>

<style scoped>
.hint {
  color: #909399;
}
</style>
