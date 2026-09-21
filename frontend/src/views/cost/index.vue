<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import dayjs from 'dayjs'
import CostPurposeChart from '@/components/charts/CostPurposeChart.vue'
import CostTrendChart from '@/components/charts/CostTrendChart.vue'
import { costApi } from '@/services/cost.api'
import { useToastStore } from '@/stores/toast'
import type {
  CostCallDetailVO,
  CostModelSliceVO,
  CostOverviewVO,
  CostPurposeSliceVO,
  CostTrendPointVO,
  CostUserSliceVO,
} from '@/types/cost'
import { BUDGET_STATUS_LABELS, LLM_PURPOSE_LABELS } from '@/utils/constants'

/**
 * 成本与配额治理看板（B-4 / 迭代 4 W5，FR-17 只读 + FR-20 ③ 配额可视化）。
 *
 * 全页只读、数据源唯一（`log_llm_call` 明细聚合），故每个数字都能在下方的明细抽样里逐行对上
 * （FR-17 AC①）。概览刻意**并列**两套百分比——DB 明细口径与 Redis 计数桶口径：正常时相等，
 * Redis 被清空或换实例时前者不变而后者归零，把口径漂移摆成可见的事实而不是掩盖它。
 * 阈值调整不在本页：日预算与消息长度走「系统配置」页（读写分离，改动必留审计）。
 */
const toast = useToastStore()

const range = ref<[string, string] | null>(null)
const granularity = ref<'DAY' | 'HOUR'>('DAY')
const detailLimit = ref(10)
const loading = ref(false)

const overview = ref<CostOverviewVO | null>(null)
const trend = ref<CostTrendPointVO[]>([])
const purposes = ref<CostPurposeSliceVO[]>([])
const models = ref<CostModelSliceVO[]>([])
const topUsers = ref<CostUserSliceVO[]>([])
const details = ref<CostCallDetailVO[]>([])

function rangeParams(): { start?: string; end?: string } {
  return range.value ? { start: range.value[0], end: range.value[1] } : {}
}

async function refresh(): Promise<void> {
  loading.value = true
  try {
    const query = rangeParams()
    const [result, points, purposeRows, modelRows, userRows, detailRows] = await Promise.all([
      costApi.overview(),
      costApi.trend({ ...query, granularity: granularity.value }),
      costApi.byPurpose(query),
      costApi.byModel(query),
      costApi.topUsers({ ...query, limit: 10 }),
      costApi.recent({ ...query, limit: detailLimit.value }),
    ])
    overview.value = result
    trend.value = points
    purposes.value = purposeRows
    models.value = modelRows
    topUsers.value = userRows
    details.value = detailRows
  } catch (error) {
    toast.error(error instanceof Error ? error.message : '成本看板加载失败')
  } finally {
    loading.value = false
  }
}

const numberFormat = new Intl.NumberFormat('zh-CN')

function num(value: number | null | undefined): string {
  return value === null || value === undefined ? '—' : numberFormat.format(value)
}

const overviewCards = computed(() => {
  const data = overview.value
  return [
    { key: 'calls', label: '今日调用', value: num(data?.todayCalls) },
    { key: 'tokens', label: '今日 token', value: num(data?.todayTokens) },
    { key: 'prompt', label: '其中输入', value: num(data?.todayPromptTokens) },
    { key: 'completion', label: '其中输出', value: num(data?.todayCompletionTokens) },
    {
      key: 'budget',
      label: '生效日预算',
      value: data && data.dailyBudget > 0 ? num(data.dailyBudget) : '未设上限',
    },
    { key: 'month', label: '本月累计', value: num(data?.monthTokens) },
  ]
})

/** 明细口径与计数口径是否已漂移（Redis 计数桶被清空的典型征状） */
const caliberDrift = computed(
  () => overview.value !== null && overview.value.usedPercent !== overview.value.counterUsagePercent,
)

const budgetStatusType = computed(() => (overview.value?.status === 'NORMAL' ? 'success' : 'warning'))

const budgetProgressStatus = computed<'success' | 'warning' | 'exception'>(() => {
  const status = overview.value?.status
  if (status === 'DEGRADED') {
    return 'exception'
  }
  return status === 'WARN' ? 'warning' : 'success'
})

function statusLabel(status?: string): string {
  return status ? BUDGET_STATUS_LABELS[status] ?? status : '—'
}

function purposeLabel(purpose: string): string {
  return LLM_PURPOSE_LABELS[purpose] ?? purpose
}

function modelLabel(model: string | null): string {
  return model ?? '供应商默认'
}

function timeText(value: string | null): string {
  return value ? dayjs(value).format('MM-DD HH:mm:ss') : '—'
}

onMounted(refresh)
</script>

<template>
  <section v-loading="loading">
    <div class="bar">
      <h2>成本与配额</h2>
      <el-date-picker
        v-model="range"
        type="datetimerange"
        value-format="YYYY-MM-DDTHH:mm:ss"
        range-separator="~"
        start-placeholder="开始"
        end-placeholder="结束"
      />
      <el-select
        v-model="granularity"
        style="width: 110px"
        @change="refresh"
      >
        <el-option
          label="按天"
          value="DAY"
        />
        <el-option
          label="按小时"
          value="HOUR"
        />
      </el-select>
      <el-button
        type="primary"
        :loading="loading"
        @click="refresh"
      >
        刷新
      </el-button>
    </div>

    <p class="hint">
      概览是「今日 / 本月」固定口径，不受区间影响；区间只作用于趋势、分布、排行与抽样。
      调整日预算请到<b>系统配置</b>页的 rate_limit 分组（免重启生效，改动留审计）。
    </p>

    <div class="cards">
      <div
        v-for="card in overviewCards"
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

    <div
      v-if="overview"
      class="budget"
    >
      <div class="budget__head">
        <span>今日预算消耗（明细口径）</span>
        <el-tag
          size="small"
          :type="budgetStatusType"
        >
          {{ statusLabel(overview.status) }}
        </el-tag>
        <el-tag
          v-if="overview.degraded"
          size="small"
          type="danger"
        >
          降级中：仅基础回复
        </el-tag>
      </div>
      <el-progress
        :percentage="Math.min(100, overview.usedPercent)"
        :status="budgetProgressStatus"
        :stroke-width="14"
      />
      <div class="budget__foot">
        <span>降级判定实际所用（Redis 计数桶口径）：{{ overview.counterUsagePercent }}%</span>
        <span
          v-if="caliberDrift"
          class="budget__drift"
        >
          与明细口径相差 {{ Math.abs(overview.usedPercent - overview.counterUsagePercent) }} 个百分点：
          通常是计数桶被清空或换了实例，此时降级判定会偏松，须复核
        </span>
      </div>
    </div>

    <h3 class="section-title">
      消耗趋势
    </h3>
    <div class="chart">
      <CostTrendChart :points="trend" />
    </div>
    <el-table
      :data="trend"
      border
      size="small"
    >
      <el-table-column
        prop="bucket"
        label="时间桶"
        width="180"
      />
      <el-table-column
        prop="calls"
        label="调用"
        width="90"
      />
      <el-table-column
        label="输入 token"
        width="140"
      >
        <template #default="{ row }">
          {{ num(row.promptTokens) }}
        </template>
      </el-table-column>
      <el-table-column
        label="输出 token"
        width="140"
      >
        <template #default="{ row }">
          {{ num(row.completionTokens) }}
        </template>
      </el-table-column>
      <el-table-column
        label="合计 token"
        min-width="140"
      >
        <template #default="{ row }">
          {{ num(row.tokens) }}
        </template>
      </el-table-column>
    </el-table>

    <h3 class="section-title">
      用途与模型分布
    </h3>
    <div class="charts">
      <div class="chart">
        <h4>按用途（token 占比）</h4>
        <CostPurposeChart :slices="purposes" />
        <el-table
          :data="purposes"
          border
          size="small"
          class="chart__table"
        >
          <el-table-column
            label="用途"
            min-width="110"
          >
            <template #default="{ row }">
              {{ purposeLabel(row.purpose) }}
            </template>
          </el-table-column>
          <el-table-column
            prop="calls"
            label="次数"
            width="80"
          />
          <el-table-column
            label="token"
            width="120"
          >
            <template #default="{ row }">
              {{ num(row.tokens) }}
            </template>
          </el-table-column>
          <el-table-column
            prop="percent"
            label="占比"
            width="90"
          >
            <template #default="{ row }">
              {{ row.percent }}%
            </template>
          </el-table-column>
        </el-table>
      </div>

      <div class="chart">
        <h4>按供应商 / 模型</h4>
        <el-table
          :data="models"
          border
          size="small"
        >
          <el-table-column
            prop="provider"
            label="供应商"
            width="110"
          />
          <el-table-column
            label="模型"
            min-width="120"
            show-overflow-tooltip
          >
            <template #default="{ row }">
              {{ modelLabel(row.model) }}
            </template>
          </el-table-column>
          <el-table-column
            prop="calls"
            label="次数"
            width="80"
          />
          <el-table-column
            label="token"
            width="120"
          >
            <template #default="{ row }">
              {{ num(row.tokens) }}
            </template>
          </el-table-column>
          <el-table-column
            label="占比"
            width="90"
          >
            <template #default="{ row }">
              {{ row.percent }}%
            </template>
          </el-table-column>
        </el-table>
        <p class="chart__note">
          模型为空表示请求未指定模型、走供应商默认；此处按后端原值分组，不做合并。
        </p>
      </div>
    </div>

    <h3 class="section-title">
      用户消耗排行（openid 已在写入侧脱敏）
    </h3>
    <el-table
      :data="topUsers"
      border
      size="small"
    >
      <el-table-column
        prop="openid"
        label="用户（脱敏）"
        min-width="180"
      />
      <el-table-column
        prop="calls"
        label="调用"
        width="100"
      />
      <el-table-column
        label="token"
        width="140"
      >
        <template #default="{ row }">
          {{ num(row.tokens) }}
        </template>
      </el-table-column>
    </el-table>
    <p class="hint">
      「(未归属)」是意图分类等不带用户标识的调用，后端单列成桶而非并入任何真实用户；
      库里存的已是脱敏值，页面不再二次打码。
    </p>

    <h3 class="section-title">
      明细抽样
    </h3>
    <p class="hint">
      看板上每个聚合值都应能在这里逐行对上（FR-17 AC①），必要时用等价 SQL 复核：
      <code>SELECT SUM(total_tokens) FROM log_llm_call WHERE created_at &gt;= 今日 00:00</code>
      —— total 由 prompt+completion 推导，与日预算同算法。
    </p>
    <el-select
      v-model="detailLimit"
      style="width: 130px"
      class="sample-size"
      @change="refresh"
    >
      <el-option
        v-for="size in [10, 20, 50]"
        :key="size"
        :label="`抽样 ${size} 行`"
        :value="size"
      />
    </el-select>
    <el-table
      :data="details"
      border
      size="small"
    >
      <el-table-column
        prop="id"
        label="ID"
        width="70"
      />
      <el-table-column
        label="时间"
        width="130"
      >
        <template #default="{ row }">
          {{ timeText(row.createdAt) }}
        </template>
      </el-table-column>
      <el-table-column
        label="用途"
        width="100"
      >
        <template #default="{ row }">
          {{ purposeLabel(row.purpose) }}
        </template>
      </el-table-column>
      <el-table-column
        prop="provider"
        label="供应商"
        width="90"
      />
      <el-table-column
        label="模型"
        min-width="120"
        show-overflow-tooltip
      >
        <template #default="{ row }">
          {{ modelLabel(row.model) }}
        </template>
      </el-table-column>
      <el-table-column
        label="用户（脱敏）"
        min-width="130"
      >
        <template #default="{ row }">
          {{ row.openid ?? '(未归属)' }}
        </template>
      </el-table-column>
      <el-table-column
        prop="promptTokens"
        label="输入"
        width="80"
      />
      <el-table-column
        prop="completionTokens"
        label="输出"
        width="80"
      />
      <el-table-column
        label="合计"
        width="90"
      >
        <template #default="{ row }">
          {{ num(row.totalTokens) }}
        </template>
      </el-table-column>
      <el-table-column
        prop="traceId"
        label="链路"
        min-width="150"
        show-overflow-tooltip
      >
        <template #default="{ row }">
          {{ row.traceId ?? '—' }}
        </template>
      </el-table-column>
    </el-table>
  </section>
</template>

<style scoped>
.bar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 8px;
}

.bar h2 {
  margin: 0;
  font-size: 18px;
}

.hint {
  margin: 4px 0 12px;
  color: #909399;
  font-size: 12px;
  line-height: 1.6;
}

.hint code {
  padding: 1px 4px;
  color: #606266;
  background: #f5f7fa;
  border-radius: 3px;
}

.cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(150px, 1fr));
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

.budget {
  padding: 12px 16px;
  margin: 16px 0;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 6px;
}

.budget__head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  font-size: 14px;
  font-weight: 600;
}

.budget__foot {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 8px;
  color: #909399;
  font-size: 12px;
}

.budget__drift {
  color: #e6a23c;
}

.section-title {
  margin: 20px 0 8px;
  font-size: 16px;
}

.charts {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(340px, 1fr));
  gap: 16px;
}

.chart {
  padding: 12px;
  margin-bottom: 12px;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 6px;
}

.chart h4 {
  margin: 0 0 8px;
  font-size: 14px;
  color: #303133;
}

.chart__table {
  margin-top: 12px;
}

.chart__note {
  margin: 8px 0 0;
  color: #909399;
  font-size: 12px;
}

.sample-size {
  margin-bottom: 8px;
}
</style>
