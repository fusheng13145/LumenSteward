<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import dayjs from 'dayjs'
import { toolLogApi } from '@/services/toolLog.api'
import { useToastStore } from '@/stores/toast'
import type { ToolLogDetailVO, ToolLogVO, ToolStatsVO } from '@/types/toolLog'
import { PAGE, TOOL_STATUS_LABELS } from '@/utils/constants'
import { maskOpenid } from '@/utils/mask'

/**
 * 工具调用日志（T05 / 架构 4.3 / AC-E5 / AC-E6）。
 *
 * 列表支持 traceId/工具名/状态/openid/时间范围检索；详情展示入参、结果、耗时与降级原因；
 * 顶部统计来自 `/api/tool-logs/stats`（COUNT/AVG 聚合，非估算）。
 */
const toast = useToastStore()

const filters = reactive<{
  traceId: string
  toolName: string
  status?: number
  openid: string
  range: [string, string] | null
}>({
  traceId: '',
  toolName: '',
  status: undefined,
  openid: '',
  range: null,
})

const page = reactive<{ page: number; pageSize: number }>({
  page: PAGE.DEFAULT_PAGE,
  pageSize: PAGE.DEFAULT_PAGE_SIZE,
})
const rows = ref<ToolLogVO[]>([])
const total = ref(0)
const loading = ref(false)
const stats = ref<ToolStatsVO | null>(null)

const detailVisible = ref(false)
const detail = ref<ToolLogDetailVO | null>(null)

const STATUS_OPTIONS = [
  { label: '成功', value: 0 },
  { label: '失败', value: 1 },
  { label: '降级', value: 2 },
  { label: '超时', value: 3 },
  { label: '未执行', value: 4 },
]

const statsCards = computed(() => {
  const data = stats.value
  return [
    { key: 'total', label: '调用总量', value: data ? String(data.total) : '—' },
    {
      key: 'successRate',
      label: '成功率',
      value: data ? `${(data.successRate * 100).toFixed(2)}%` : '—',
    },
    { key: 'degraded', label: '降级数', value: data ? String(data.degraded) : '—' },
    { key: 'timeout', label: '超时数', value: data ? String(data.timeout) : '—' },
    {
      key: 'avgLatencyMs',
      label: '平均耗时',
      value: data ? `${data.avgLatencyMs.toFixed(0)} ms` : '—',
    },
  ]
})

function statusTag(status: number): 'success' | 'danger' | 'warning' | 'info' {
  if (status === 0) {
    return 'success'
  }
  if (status === 2) {
    return 'warning'
  }
  if (status === 3 || status === 1) {
    return 'danger'
  }
  return 'info'
}

function currentQuery() {
  return {
    page: page.page,
    pageSize: page.pageSize,
    traceId: filters.traceId || undefined,
    toolName: filters.toolName || undefined,
    status: filters.status,
    openid: filters.openid || undefined,
    startTime: filters.range ? filters.range[0] : undefined,
    endTime: filters.range ? filters.range[1] : undefined,
  }
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const result = await toolLogApi.list(currentQuery())
    rows.value = result.list
    total.value = result.total
  } catch (error) {
    toast.error(error instanceof Error ? error.message : '工具日志加载失败')
  } finally {
    loading.value = false
  }
}

async function loadStats(): Promise<void> {
  try {
    stats.value = await toolLogApi.stats({
      toolName: filters.toolName || undefined,
      startTime: filters.range ? filters.range[0] : undefined,
      endTime: filters.range ? filters.range[1] : undefined,
    })
  } catch {
    stats.value = null
  }
}

function search(): void {
  page.page = PAGE.DEFAULT_PAGE
  void Promise.all([load(), loadStats()])
}

async function openDetail(row: ToolLogVO): Promise<void> {
  try {
    detail.value = await toolLogApi.detail(row.id)
    detailVisible.value = true
  } catch (error) {
    toast.error(error instanceof Error ? error.message : '详情加载失败')
  }
}

onMounted(() => {
  void load()
  void loadStats()
})
</script>

<template>
  <section>
    <h2>工具调用日志</h2>

    <div class="cards">
      <div
        v-for="card in statsCards"
        :key="card.key"
        class="card"
      >
        <div class="card__label">
          {{ card.label }}
        </div>
        <div class="card__value">
          {{ card.value }}
        </div>
      </div>
    </div>

    <el-form
      class="filters"
      inline
      @submit.prevent="search"
    >
      <el-form-item label="traceId">
        <el-input
          v-model="filters.traceId"
          clearable
          style="width: 180px"
        />
      </el-form-item>
      <el-form-item label="工具名">
        <el-input
          v-model="filters.toolName"
          clearable
          style="width: 160px"
        />
      </el-form-item>
      <el-form-item label="状态">
        <el-select
          v-model="filters.status"
          placeholder="全部"
          clearable
          style="width: 120px"
        >
          <el-option
            v-for="item in STATUS_OPTIONS"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="openid">
        <el-input
          v-model="filters.openid"
          clearable
          style="width: 180px"
        />
      </el-form-item>
      <el-form-item label="时间范围">
        <el-date-picker
          v-model="filters.range"
          type="datetimerange"
          value-format="YYYY-MM-DDTHH:mm:ss"
          range-separator="~"
          start-placeholder="开始"
          end-placeholder="结束"
        />
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
        width="80"
      />
      <el-table-column
        prop="traceId"
        label="traceId"
        min-width="180"
        show-overflow-tooltip
      />
      <el-table-column
        prop="toolName"
        label="工具"
        width="170"
      />
      <el-table-column
        label="状态"
        width="90"
      >
        <template #default="{ row }">
          <el-tag
            :type="statusTag(row.status)"
            size="small"
          >
            {{ TOOL_STATUS_LABELS[row.status] ?? row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column
        prop="errorType"
        label="异常层"
        width="90"
      />
      <el-table-column
        prop="fallbackReason"
        label="降级原因"
        min-width="150"
        show-overflow-tooltip
      />
      <el-table-column
        prop="latencyMs"
        label="耗时(ms)"
        width="100"
      />
      <el-table-column
        label="openid"
        width="150"
      >
        <template #default="{ row }">
          {{ maskOpenid(row.openid) }}
        </template>
      </el-table-column>
      <el-table-column
        label="时间"
        width="170"
      >
        <template #default="{ row }">
          {{ row.createdAt ? dayjs(row.createdAt).format('YYYY-MM-DD HH:mm:ss') : '—' }}
        </template>
      </el-table-column>
      <el-table-column
        label="操作"
        width="90"
        fixed="right"
      >
        <template #default="{ row }">
          <el-button
            link
            type="primary"
            @click="openDetail(row)"
          >
            详情
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

    <el-dialog
      v-model="detailVisible"
      title="工具调用详情"
      width="640px"
    >
      <el-descriptions
        v-if="detail"
        :column="2"
        border
        size="small"
      >
        <el-descriptions-item label="traceId">
          {{ detail.traceId }}
        </el-descriptions-item>
        <el-descriptions-item label="工具名">
          {{ detail.toolName }}
        </el-descriptions-item>
        <el-descriptions-item label="状态">
          {{ TOOL_STATUS_LABELS[detail.status] ?? detail.status }}
        </el-descriptions-item>
        <el-descriptions-item label="异常层">
          {{ detail.errorType || '—' }}
        </el-descriptions-item>
        <el-descriptions-item label="耗时(ms)">
          {{ detail.latencyMs ?? '—' }}
        </el-descriptions-item>
        <el-descriptions-item label="Agent 轮次">
          {{ detail.llmRound ?? '—' }}
        </el-descriptions-item>
        <el-descriptions-item
          label="降级原因"
          :span="2"
        >
          {{ detail.fallbackReason || '—' }}
        </el-descriptions-item>
        <el-descriptions-item
          label="入参"
          :span="2"
        >
          <pre class="json">{{ detail.paramsJson || '—' }}</pre>
        </el-descriptions-item>
        <el-descriptions-item
          label="结果"
          :span="2"
        >
          <pre class="json">{{ detail.resultJson || '—' }}</pre>
        </el-descriptions-item>
      </el-descriptions>
    </el-dialog>
  </section>
</template>

<style scoped>
.cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: 12px;
  margin: 16px 0;
}

.card {
  padding: 12px 16px;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 6px;
}

.card__label {
  color: #909399;
  font-size: 13px;
}

.card__value {
  margin-top: 4px;
  font-size: 20px;
  font-weight: 600;
}

.filters {
  margin-bottom: 12px;
}

.pager {
  margin-top: 12px;
}

.json {
  max-height: 160px;
  margin: 0;
  overflow: auto;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
