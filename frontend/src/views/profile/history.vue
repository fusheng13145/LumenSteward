<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import dayjs from 'dayjs'
import { profileApi } from '@/services/profile.api'
import { useToastStore } from '@/stores/toast'
import type { AuditLogVO } from '@/types/audit'
import type { ProfileChangeVO, ProfileHistoryQuery } from '@/types/pet'
import { PAGE } from '@/utils/constants'

/**
 * 档案变更留痕（A-4 / T7 / 迭代 3 Wave 2）。
 *
 * 复用 FR-16 审计范式：档案写操作在后端记字段级 diff（reg_type=PROFILE），
 * 本页按 openid（脱敏匹配）/action 检索并着色展示 before→after。
 */
const toast = useToastStore()

const filters = reactive<{ openid: string; action: string }>({
  openid: '',
  action: '',
})

const page = reactive<{ page: number; pageSize: number }>({
  page: PAGE.DEFAULT_PAGE,
  pageSize: PAGE.DEFAULT_PAGE_SIZE,
})
const rows = ref<AuditLogVO[]>([])
const total = ref(0)
const loading = ref(false)

const ACTION_OPTIONS = ['CREATE', 'UPDATE', 'DELETE']

/** 字段中文名（snake_case 对齐后端 biz_pet_profile 列名）。 */
const FIELD_LABELS: Record<string, string> = {
  pet_name: '昵称',
  pet_type: '类型',
  breed: '品种',
  gender: '性别',
  birthday: '生日',
  weight_kg: '体重',
  personality: '性格',
  notes: '备注',
  photo_media_id: '头像',
}

function fieldLabel(field: string): string {
  return FIELD_LABELS[field] ?? field
}

function safeParse(json: string | null): Record<string, unknown> {
  if (!json) {
    return {}
  }
  try {
    const obj = JSON.parse(json)
    return obj && typeof obj === 'object' ? (obj as Record<string, unknown>) : {}
  } catch {
    return {}
  }
}

/** 将 beforeValue/afterValue 两个字段级 diff JSON 解析并合并为可展示的变更列表。 */
function parseDiff(row: AuditLogVO): ProfileChangeVO[] {
  const before = safeParse(row.beforeValue)
  const after = safeParse(row.afterValue)
  const fields = new Set<string>([...Object.keys(before), ...Object.keys(after)])
  const changes: ProfileChangeVO[] = []
  fields.forEach((field) => {
    changes.push({
      field,
      before: field in before ? String(before[field] ?? '') : null,
      after: field in after ? String(after[field] ?? '') : null,
    })
  })
  return changes
}

function actionTag(action: string): 'success' | 'warning' | 'danger' | 'info' {
  if (action === 'CREATE') return 'success'
  if (action === 'UPDATE') return 'warning'
  if (action === 'DELETE') return 'danger'
  return 'info'
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const params: ProfileHistoryQuery = {
      page: page.page,
      pageSize: page.pageSize,
      openid: filters.openid || undefined,
      action: filters.action || undefined,
    }
    const result = await profileApi.getProfileHistory(params)
    rows.value = result.list
    total.value = result.total
  } catch (error) {
    toast.error(error instanceof Error ? error.message : '档案变更历史加载失败')
  } finally {
    loading.value = false
  }
}

function search(): void {
  page.page = PAGE.DEFAULT_PAGE
  void load()
}

onMounted(load)
</script>

<template>
  <section>
    <h2>档案变更留痕</h2>

    <el-form
      class="filters"
      inline
      @submit.prevent="search"
    >
      <el-form-item label="openid">
        <el-input
          v-model="filters.openid"
          clearable
          placeholder="输入完整 openid（脱敏后匹配）"
          style="width: 240px"
        />
      </el-form-item>
      <el-form-item label="操作">
        <el-select
          v-model="filters.action"
          placeholder="全部"
          clearable
          style="width: 140px"
        >
          <el-option
            v-for="item in ACTION_OPTIONS"
            :key="item"
            :label="item"
            :value="item"
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
        width="80"
      />
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
        width="110"
      >
        <template #default="{ row }">
          <el-tag
            :type="actionTag(row.action)"
            size="small"
          >
            {{ row.action }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column
        prop="target"
        label="对象"
        min-width="140"
        show-overflow-tooltip
      />
      <el-table-column
        label="变更字段"
        min-width="320"
      >
        <template #default="{ row }">
          <div
            v-if="parseDiff(row).length"
            class="changes"
          >
            <div
              v-for="c in parseDiff(row)"
              :key="c.field"
              class="change"
            >
              <template v-if="c.before === null">
                <span class="add">+ {{ fieldLabel(c.field) }} = {{ c.after }}</span>
              </template>
              <template v-else-if="c.after === null">
                <span class="del">- {{ fieldLabel(c.field) }}（原 {{ c.before }}）</span>
              </template>
              <template v-else>
                <span class="field">{{ fieldLabel(c.field) }}</span>：
                <span class="before">{{ c.before }}</span>
                <span class="arrow">→</span>
                <span class="after">{{ c.after }}</span>
              </template>
            </div>
          </div>
          <span v-else>无字段变更</span>
        </template>
      </el-table-column>
      <el-table-column
        prop="adminId"
        label="操作人"
        width="90"
      />
      <el-table-column
        label="结果"
        width="90"
      >
        <template #default="{ row }">
          <el-tag
            :type="row.result === 1 ? 'success' : 'danger'"
            size="small"
          >
            {{ row.result === 1 ? '成功' : '失败' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column
        prop="reason"
        label="原因"
        min-width="140"
        show-overflow-tooltip
      />
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

.changes {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.change {
  line-height: 1.6;
}

.field {
  color: #606266;
}

.before {
  color: #f56c6c;
  text-decoration: line-through;
}

.arrow {
  color: #909399;
  margin: 0 4px;
}

.after {
  color: #67c23a;
}

.add {
  color: #67c23a;
}

.del {
  color: #f56c6c;
}
</style>
