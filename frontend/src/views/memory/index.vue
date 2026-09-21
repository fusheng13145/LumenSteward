<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import dayjs from 'dayjs'
import { memoryApi } from '@/services/memory.api'
import { useToastStore } from '@/stores/toast'
import { PERMISSIONS } from '@/config/permissions'
import type { MemoryItemVO } from '@/types/memory'
import {
  MEMORY_KIND_LABELS,
  MEMORY_ORIGIN_LABELS,
  MEMORY_STATUS,
  MEMORY_STATUS_LABELS,
  PAGE,
} from '@/utils/constants'
import { maskOpenid } from '@/utils/mask'

/**
 * 个人状态库只读后台（迭代 4 W6-b / §2.19）。
 *
 * 列表为只读（口径同监控，三角色可见）；「删除」是误抽取条目的人工纠错出口，
 * 仅 SUPER_ADMIN 可见（v-permission），后端 @PreAuthorize 独立兜底。
 * 不提供编辑：修正语义由生长管道的「覆盖」承担。
 */
const toast = useToastStore()

const filters = reactive<{ openid: string; kind?: string; status?: string }>({
  openid: '',
  kind: undefined,
  status: undefined,
})
const page = reactive<{ page: number; pageSize: number }>({
  page: PAGE.DEFAULT_PAGE,
  pageSize: PAGE.DEFAULT_PAGE_SIZE,
})
const rows = ref<MemoryItemVO[]>([])
const total = ref(0)
const loading = ref(false)

const KIND_OPTIONS = Object.entries(MEMORY_KIND_LABELS).map(([value, label]) => ({ value, label }))
const STATUS_OPTIONS = [
  { value: MEMORY_STATUS.ACTIVE, label: MEMORY_STATUS_LABELS.ACTIVE },
  { value: MEMORY_STATUS.SUPERSEDED, label: MEMORY_STATUS_LABELS.SUPERSEDED },
]

async function load(): Promise<void> {
  loading.value = true
  try {
    const result = await memoryApi.list({
      page: page.page,
      pageSize: page.pageSize,
      openid: filters.openid || undefined,
      kind: filters.kind,
      status: filters.status,
    })
    rows.value = result.list
    total.value = result.total
  } catch (error) {
    toast.error(error instanceof Error ? error.message : '状态库列表加载失败')
  } finally {
    loading.value = false
  }
}

function search(): void {
  page.page = PAGE.DEFAULT_PAGE
  void load()
}

async function remove(item: MemoryItemVO): Promise<void> {
  try {
    await memoryApi.remove(item.id)
    toast.success(`已删除条目 #${item.id}（逻辑删除，审计留痕）`)
    void load()
  } catch (error) {
    toast.error(error instanceof Error ? error.message : '删除失败')
  }
}

function kindLabel(kind: string): string {
  return MEMORY_KIND_LABELS[kind] ?? kind
}

function originLabel(origin: string): string {
  return MEMORY_ORIGIN_LABELS[origin] ?? origin
}

function confidenceText(value: MemoryItemVO['confidence']): string {
  return value === null || value === undefined ? '—' : Number(value).toFixed(2)
}

onMounted(load)
</script>

<template>
  <section>
    <h2>个人状态库</h2>

    <el-form
      class="filters"
      inline
      @submit.prevent="search"
    >
      <el-form-item label="openid">
        <el-input
          v-model="filters.openid"
          placeholder="精确匹配"
          clearable
          style="width: 200px"
        />
      </el-form-item>
      <el-form-item label="类型">
        <el-select
          v-model="filters.kind"
          placeholder="全部"
          clearable
          style="width: 120px"
        >
          <el-option
            v-for="item in KIND_OPTIONS"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="状态">
        <el-select
          v-model="filters.status"
          placeholder="全部"
          clearable
          style="width: 110px"
        >
          <el-option
            v-for="item in STATUS_OPTIONS"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button
          type="primary"
          @click="search"
        >
          查询
        </el-button>
      </el-form-item>
    </el-form>

    <el-table
      v-loading="loading"
      :data="rows"
      border
      size="small"
    >
      <el-table-column
        prop="id"
        label="ID"
        width="70"
      />
      <el-table-column
        label="openid（脱敏）"
        min-width="140"
      >
        <template #default="{ row }">
          {{ maskOpenid(row.openid) }}
        </template>
      </el-table-column>
      <el-table-column
        label="类型"
        width="80"
      >
        <template #default="{ row }">
          {{ kindLabel(row.kind) }}
        </template>
      </el-table-column>
      <el-table-column
        prop="name"
        label="名称"
        min-width="110"
        show-overflow-tooltip
      />
      <el-table-column
        prop="content"
        label="事实正文"
        min-width="180"
        show-overflow-tooltip
      />
      <el-table-column
        label="来源"
        width="90"
      >
        <template #default="{ row }">
          {{ originLabel(row.origin) }}
        </template>
      </el-table-column>
      <el-table-column
        label="置信度"
        width="80"
      >
        <template #default="{ row }">
          {{ confidenceText(row.confidence) }}
        </template>
      </el-table-column>
      <el-table-column
        prop="hitCount"
        label="提及"
        width="60"
      />
      <el-table-column
        label="状态"
        width="80"
      >
        <template #default="{ row }">
          {{ MEMORY_STATUS_LABELS[row.status] ?? row.status }}
        </template>
      </el-table-column>
      <el-table-column
        label="溯源"
        min-width="150"
        show-overflow-tooltip
      >
        <template #default="{ row }">
          {{ row.sourceTraceId || '—' }}
        </template>
      </el-table-column>
      <el-table-column
        label="最近出现"
        width="150"
      >
        <template #default="{ row }">
          {{ row.lastSeenAt ? dayjs(row.lastSeenAt).format('YYYY-MM-DD HH:mm') : '—' }}
        </template>
      </el-table-column>
      <el-table-column
        label="操作"
        width="90"
        fixed="right"
      >
        <template #default="{ row }">
          <el-button
            v-permission="PERMISSIONS.MEMORY_DELETE"
            link
            type="danger"
            @click="remove(row)"
          >
            删除
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      class="pager"
      layout="total, prev, pager, next"
      :total="total"
      :current-page="page.page"
      :page-size="page.pageSize"
      @current-change="(value: number) => { page.page = value; load() }"
    />
  </section>
</template>

<style scoped>
.filters {
  margin-bottom: 12px;
}

.pager {
  margin-top: 12px;
}
</style>
