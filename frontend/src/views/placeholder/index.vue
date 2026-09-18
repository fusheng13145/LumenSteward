<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'

/**
 * 通用占位页（T01 路由骨架）：用于后续迭代实现的后台页面，保证菜单导航可达、权限守卫可验证。
 * 具体业务页面在 T05 落地后逐一替换本组件。
 */
const route = useRoute()
const title = computed(() => route.meta.title ?? '功能')
</script>

<template>
  <section>
    <h2>{{ title }}</h2>
    <p class="hint">
      该功能将在后续迭代实现（T01 仅提供路由与权限骨架）。
    </p>
  </section>
</template>

<style scoped>
.hint {
  color: #909399;
}
</style>
