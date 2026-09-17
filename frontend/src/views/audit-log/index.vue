<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import dayjs from 'dayjs'
import { auditApi } from '@/services/audit.api'
import { useToastStore } from '@/stores/toast'
import type { AuditLogVO } from '@/types/audit'
import { PAGE } from '@/utils/constants'

/**
 * 审计日志（T05 / 架构 4.3；AUDITOR+ 只读，AC-E9）。
 */
const toast = useToastStore()

const filters = reactive<{
  regType?: string
  action: string
  range: [string, string] | null
}>({
  regType: undefined,
  action: '',
  range: null,
})

const page = reactive<{ page: number; pageSize: number }>({
  page: PAGE.DEFAULT_PAGE,
  pageSize: PAGE.DEFAULT_PAGE_SIZE,
})
const rows = ref<AuditLogVO[]>([])
const total = ref(0)
const loading = ref(false)

const REG_TYPE_OPTIONS = ['CONFIG', 'USER', 'PROFILE', 'AUTH', 'DATA_DELETE']

async function load(): Promise<void> {
  loading.value = true
  try {
    const result = await auditApi.list({
      page: page.page,
      pageSize: page.pageSize,
      regType: filters.regType,
      action: filters.action || undefined,
      startTime: filters.range ? filters.range[0] : undefined,
      endTime: filters.range ? filters.range[1] : undefined,
    })
    rows.value = result.list
    total.value = result.total
  } catch (error) {
    toast.error(error instanceof Error ? error.message : '审计日志加载失败')
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
    <h2>审计日志</h2>

    <el-form class="filters" inline @submit.prevent="search">
      <el-form-item label="资源类型">
        <el-select v-model="filters.regType" placeholder="全部" clearable style="width: 150px">
          <el-option v-for="item in REG_TYPE_OPTIONS" :key="item" :label="item" :value="item" />
        </el-select>
      </el-form-item>
      <el-form-item label="操作">
        <el-input v-model="filters.action" clearable style="width: 170px" />
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
        <el-button type="primary" @click="search">查询</el-button>
      </el-form-item>
    </el-form>

    <el-table v-loading="loading" :data="rows" border size="small">
      <el-table-column prop="id" label="ID" width="80" />
      <el-table-column label="时间" width="170">
        <template #default="{ row }">
          {{ row.createdAt ? dayjs(row.createdAt).format('YYYY-MM-DD HH:mm:ss') : '—' }}
        </template>
      </el-table-column>
      <el-table-column prop="regType" label="资源类型" width="120" />
      <el-table-column prop="action" label="操作" width="160" />
      <el-table-column prop="target" label="对象" min-width="140" show-overflow-tooltip />
      <el-table-column prop="adminId" label="操作人" width="90" />
      <el-table-column prop="ip" label="IP" width="140" />
      <el-table-column label="结果" width="90">
        <template #default="{ row }">
          <el-tag :type="row.result === 1 ? 'success' : 'danger'" size="small">
            {{ row.result === 1 ? '成功' : '失败' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="reason" label="原因" min-width="160" show-overflow-tooltip />
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
