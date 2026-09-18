<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import dayjs from 'dayjs'
import { PERMISSIONS } from '@/config/permissions'
import { userApi } from '@/services/user.api'
import { useToastStore } from '@/stores/toast'
import type { UserVO } from '@/types/user'
import { PAGE } from '@/utils/constants'
import { maskOpenid } from '@/utils/mask'

/**
 * 用户列表（T05 / 架构 4.3）。
 *
 * 只读列表对全部角色开放；「启用/禁用」「导出」仅 SUPER_ADMIN（`v-permission` 控制入口，
 * 后端 `@PreAuthorize` 兜底）。
 */
const router = useRouter()
const toast = useToastStore()

const filters = reactive<{ keyword: string; status?: number }>({
  keyword: '',
  status: undefined,
})
const page = reactive<{ page: number; pageSize: number }>({
  page: PAGE.DEFAULT_PAGE,
  pageSize: PAGE.DEFAULT_PAGE_SIZE,
})
const rows = ref<UserVO[]>([])
const total = ref(0)
const loading = ref(false)

async function load(): Promise<void> {
  loading.value = true
  try {
    const result = await userApi.list({
      page: page.page,
      pageSize: page.pageSize,
      keyword: filters.keyword || undefined,
      status: filters.status,
    })
    rows.value = result.list
    total.value = result.total
  } catch (error) {
    toast.error(error instanceof Error ? error.message : '用户列表加载失败')
  } finally {
    loading.value = false
  }
}

function search(): void {
  page.page = PAGE.DEFAULT_PAGE
  void load()
}

function openDetail(id: number): void {
  void router.push({ name: 'user-detail', params: { id: String(id) } })
}

async function toggleStatus(row: UserVO): Promise<void> {
  const next = row.status === 1 ? 0 : 1
  try {
    await userApi.updateStatus(row.id, { status: next })
    toast.success(next === 1 ? '已启用' : '已禁用')
    await load()
  } catch (error) {
    toast.error(error instanceof Error ? error.message : '操作失败')
  }
}

async function exportCsv(): Promise<void> {
  try {
    const blob = await userApi.exportCsv({
      page: PAGE.DEFAULT_PAGE,
      pageSize: PAGE.MAX_PAGE_SIZE,
      keyword: filters.keyword || undefined,
      status: filters.status,
    })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = 'users.csv'
    link.click()
    URL.revokeObjectURL(url)
  } catch (error) {
    toast.error(error instanceof Error ? error.message : '导出失败')
  }
}

onMounted(load)
</script>

<template>
  <section>
    <h2>用户管理</h2>

    <el-form
      class="filters"
      inline
      @submit.prevent="search"
    >
      <el-form-item label="关键字">
        <el-input
          v-model="filters.keyword"
          placeholder="openid / 昵称"
          clearable
          style="width: 200px"
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
            label="正常"
            :value="1"
          />
          <el-option
            label="禁用"
            :value="0"
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
        <el-button
          v-permission="PERMISSIONS.USER_WRITE"
          @click="exportCsv"
        >
          导出 CSV
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
        prop="nickname"
        label="昵称"
        min-width="120"
      />
      <el-table-column
        label="状态"
        width="90"
      >
        <template #default="{ row }">
          <el-tag
            :type="row.status === 1 ? 'success' : 'danger'"
            size="small"
          >
            {{ row.status === 1 ? '正常' : '禁用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column
        label="最后交互"
        width="170"
      >
        <template #default="{ row }">
          {{ row.lastInteractAt ? dayjs(row.lastInteractAt).format('YYYY-MM-DD HH:mm') : '—' }}
        </template>
      </el-table-column>
      <el-table-column
        label="操作"
        width="180"
        fixed="right"
      >
        <template #default="{ row }">
          <el-button
            link
            type="primary"
            @click="openDetail(row.id)"
          >
            详情
          </el-button>
          <el-button
            v-permission="PERMISSIONS.USER_WRITE"
            link
            :type="row.status === 1 ? 'danger' : 'success'"
            @click="toggleStatus(row)"
          >
            {{ row.status === 1 ? '禁用' : '启用' }}
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
