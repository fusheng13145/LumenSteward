<script setup lang="ts">
import { onMounted, ref } from 'vue'
import dayjs from 'dayjs'
import { useConfigStore } from '@/stores/config'
import { useToastStore } from '@/stores/toast'

/**
 * 系统配置展示（T05 / 架构 4.3；SUPER_ADMIN 独占）。
 *
 * 顶部标识当前运行模式（Mock/Real，AC-D3 零代码切换）；列表只读展示，
 * `SECRET` 值由后端脱敏为尾号（G-11）。写接口为 MVP 骨架（仅落库、不热更新，G-33）。
 */
const configStore = useConfigStore()
const toast = useToastStore()
const loading = ref(false)

async function load(): Promise<void> {
  loading.value = true
  try {
    await configStore.load()
  } catch (error) {
    toast.error(error instanceof Error ? error.message : '配置加载失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <section v-loading="loading">
    <h2>系统配置</h2>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      class="mode"
      :title="`运行模式：LLM=${configStore.llmProvider || '未加载'} · 微信通道=${configStore.wechatMockEnabled ? 'Mock' : 'Real'}`"
      description="MVP 边界：配置为只读展示，写接口仅落库不热更新（切换 Mock/Real 需重启）。SECRET 值仅返回尾号。"
    />

    <el-button class="refresh" :loading="loading" @click="load">刷新</el-button>

    <el-table :data="configStore.persistedItems" border size="small">
      <el-table-column prop="configKey" label="键名" min-width="200" />
      <el-table-column prop="configValue" label="值" min-width="160" show-overflow-tooltip />
      <el-table-column prop="valueType" label="类型" width="100" />
      <el-table-column prop="category" label="分类" width="110" />
      <el-table-column prop="description" label="说明" min-width="220" show-overflow-tooltip />
      <el-table-column label="加密" width="80">
        <template #default="{ row }">
          <el-tag :type="row.encrypted ? 'warning' : 'info'" size="small">
            {{ row.encrypted ? '是' : '否' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="更新时间" width="170">
        <template #default="{ row }">
          {{ row.updatedAt ? dayjs(row.updatedAt).format('YYYY-MM-DD HH:mm:ss') : '—' }}
        </template>
      </el-table-column>
    </el-table>
  </section>
</template>

<style scoped>
.mode {
  margin: 12px 0;
}

.refresh {
  margin-bottom: 12px;
}
</style>
