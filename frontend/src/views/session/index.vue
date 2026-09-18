<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import dayjs from 'dayjs'
import { sessionApi } from '@/services/session.api'
import { useToastStore } from '@/stores/toast'
import type { SessionVO } from '@/types/session'
import { PAGE, SESSION_STATE } from '@/utils/constants'
import { maskOpenid } from '@/utils/mask'

/**
 * 会话监控（T05 / 架构 4.3）。全部角色可读（AC-E9）。
 */
const router = useRouter()
const toast = useToastStore()

const filters = reactive<{ openid: string; state?: string }>({ openid: '', state: undefined })
const page = reactive<{ page: number; pageSize: number }>({
  page: PAGE.DEFAULT_PAGE,
  pageSize: PAGE.DEFAULT_PAGE_SIZE,
})
const rows = ref<SessionVO[]>([])
const total = ref(0)
const loading = ref(false)

const STATE_OPTIONS = [
  { label: '空闲', value: SESSION_STATE.IDLE },
  { label: '闲聊中', value: SESSION_STATE.CHATTING },
  { label: '任务执行中', value: SESSION_STATE.TASKING },
  { label: '降级', value: SESSION_STATE.DEGRADED },
]

async function load(): Promise<void> {
  loading.value = true
  try {
    const result = await sessionApi.list({
      page: page.page,
      pageSize: page.pageSize,
      openid: filters.openid || undefined,
      state: filters.state,
    })
    rows.value = result.list
    total.value = result.total
  } catch (error) {
    toast.error(error instanceof Error ? error.message : '会话列表加载失败')
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
    <h2>会话监控</h2>

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
      <el-form-item label="状态">
        <el-select
          v-model="filters.state"
          placeholder="全部"
          clearable
          style="width: 140px"
        >
          <el-option
            v-for="item in STATE_OPTIONS"
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
        width="80"
      />
      <el-table-column
        label="openid（脱敏）"
        min-width="150"
      >
        <template #default="{ row }">
          {{ maskOpenid(row.openid) }}
        </template>
      </el-table-column>
      <el-table-column
        prop="state"
        label="状态"
        width="110"
      />
      <el-table-column
        prop="turnCount"
        label="轮次"
        width="90"
      />
      <el-table-column
        prop="contextKey"
        label="上下文键"
        min-width="160"
        show-overflow-tooltip
      />
      <el-table-column
        label="最后活跃"
        width="170"
      >
        <template #default="{ row }">
          {{ row.lastActiveAt ? dayjs(row.lastActiveAt).format('YYYY-MM-DD HH:mm') : '—' }}
        </template>
      </el-table-column>
      <el-table-column
        label="操作"
        width="110"
        fixed="right"
      >
        <template #default="{ row }">
          <el-button
            link
            type="primary"
            @click="router.push({ name: 'session-detail', params: { id: String(row.id) } })"
          >
            消息流
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
