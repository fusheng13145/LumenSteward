<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import dayjs from 'dayjs'
import { PERMISSIONS } from '@/config/permissions'
import { petApi } from '@/services/pet.api'
import { userApi } from '@/services/user.api'
import { useToastStore } from '@/stores/toast'
import type { PetCreateRequest, PetVO } from '@/types/pet'
import type { UserDetailVO } from '@/types/user'
import { maskOpenid } from '@/utils/mask'

/**
 * 用户详情 + 宠物档案管理（T05 / 架构 4.3 / FR-14）。
 *
 * 档案写（增/改/删）为 OPERATOR+；`v-permission` 控制入口，后端 `@PreAuthorize` 兜底。
 */
const route = useRoute()
const router = useRouter()
const toast = useToastStore()

const userId = Number(route.params.id)
const detail = ref<UserDetailVO | null>(null)
const pets = ref<PetVO[]>([])
const loading = ref(false)

const dialogVisible = ref(false)
const editing = ref<PetVO | null>(null)
const form = reactive<PetCreateRequest>({
  petName: '',
  petType: undefined,
  breed: undefined,
  gender: undefined,
  birthday: undefined,
  weightKg: undefined,
  personality: undefined,
  notes: undefined,
})
const submitting = ref(false)

const PET_TYPES = ['猫', '狗', '其他']
const GENDERS = ['公', '母', '未知']

async function load(): Promise<void> {
  loading.value = true
  try {
    const [userDetail, petList] = await Promise.all([
      userApi.detail(userId),
      petApi.listByUser(userId),
    ])
    detail.value = userDetail
    pets.value = petList
  } catch (error) {
    toast.error(error instanceof Error ? error.message : '加载失败')
  } finally {
    loading.value = false
  }
}

function openCreate(): void {
  editing.value = null
  Object.assign(form, {
    petName: '',
    petType: undefined,
    breed: undefined,
    gender: undefined,
    birthday: undefined,
    weightKg: undefined,
    personality: undefined,
    notes: undefined,
  })
  dialogVisible.value = true
}

function openEdit(pet: PetVO): void {
  editing.value = pet
  Object.assign(form, {
    petName: pet.petName,
    petType: pet.petType ?? undefined,
    breed: pet.breed ?? undefined,
    gender: pet.gender ?? undefined,
    birthday: pet.birthday ?? undefined,
    weightKg: pet.weightKg ?? undefined,
    personality: pet.personality ?? undefined,
    notes: pet.notes ?? undefined,
  })
  dialogVisible.value = true
}

async function submit(): Promise<void> {
  if (!form.petName) {
    toast.warning('请填写宠物昵称')
    return
  }
  submitting.value = true
  try {
    if (editing.value) {
      const { petName: _ignored, ...patch } = form
      await petApi.update(editing.value.id, patch)
      toast.success('档案已更新')
    } else {
      await petApi.create(userId, form)
      toast.success('档案已创建')
    }
    dialogVisible.value = false
    await load()
  } catch (error) {
    toast.error(error instanceof Error ? error.message : '保存失败')
  } finally {
    submitting.value = false
  }
}

async function remove(pet: PetVO): Promise<void> {
  try {
    await petApi.remove(pet.id)
    toast.success('已删除（软删除，可重建同名）')
    await load()
  } catch (error) {
    toast.error(error instanceof Error ? error.message : '删除失败')
  }
}

onMounted(load)
</script>

<template>
  <section v-loading="loading">
    <el-page-header content="用户详情" @back="router.back()" />

    <el-descriptions v-if="detail" class="desc" :column="3" border size="small">
      <el-descriptions-item label="ID">{{ detail.id }}</el-descriptions-item>
      <el-descriptions-item label="openid（脱敏）">{{ maskOpenid(detail.openid) }}</el-descriptions-item>
      <el-descriptions-item label="昵称">{{ detail.nickname || '—' }}</el-descriptions-item>
      <el-descriptions-item label="状态">{{ detail.status === 1 ? '正常' : '禁用' }}</el-descriptions-item>
      <el-descriptions-item label="宠物数">{{ detail.petCount }}</el-descriptions-item>
      <el-descriptions-item label="会话数">{{ detail.sessionCount }}</el-descriptions-item>
      <el-descriptions-item label="工具调用数">{{ detail.toolCallCount }}</el-descriptions-item>
      <el-descriptions-item label="最后交互">
        {{ detail.lastInteractAt ? dayjs(detail.lastInteractAt).format('YYYY-MM-DD HH:mm') : '—' }}
      </el-descriptions-item>
    </el-descriptions>

    <div class="toolbar">
      <h3>宠物档案</h3>
      <el-button v-permission="PERMISSIONS.PROFILE_WRITE" type="primary" @click="openCreate">
        新增档案
      </el-button>
    </div>

    <el-table :data="pets" border size="small">
      <el-table-column prop="petName" label="昵称" min-width="100" />
      <el-table-column prop="petType" label="类型" width="90" />
      <el-table-column prop="breed" label="品种" min-width="100" />
      <el-table-column prop="gender" label="性别" width="90" />
      <el-table-column prop="birthday" label="生日" width="120" />
      <el-table-column prop="weightKg" label="体重(kg)" width="100" />
      <el-table-column prop="personality" label="性格" min-width="120" show-overflow-tooltip />
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button v-permission="PERMISSIONS.PROFILE_WRITE" link type="primary" @click="openEdit(row)">
            编辑
          </el-button>
          <el-button v-permission="PERMISSIONS.PROFILE_WRITE" link type="danger" @click="remove(row)">
            删除
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="dialogVisible" :title="editing ? '编辑档案' : '新增档案'" width="480px">
      <el-form label-width="80px">
        <el-form-item label="昵称" required>
          <el-input v-model="form.petName" maxlength="32" :disabled="Boolean(editing)" />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="form.petType" clearable placeholder="请选择" style="width: 100%">
            <el-option v-for="item in PET_TYPES" :key="item" :label="item" :value="item" />
          </el-select>
        </el-form-item>
        <el-form-item label="品种">
          <el-input v-model="form.breed" />
        </el-form-item>
        <el-form-item label="性别">
          <el-select v-model="form.gender" clearable placeholder="请选择" style="width: 100%">
            <el-option v-for="item in GENDERS" :key="item" :label="item" :value="item" />
          </el-select>
        </el-form-item>
        <el-form-item label="生日">
          <el-date-picker
            v-model="form.birthday"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="不得晚于今日"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="体重(kg)">
          <el-input-number v-model="form.weightKg" :min="0.1" :step="0.1" :precision="2" />
        </el-form-item>
        <el-form-item label="性格">
          <el-input v-model="form.personality" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.notes" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.desc {
  margin: 16px 0;
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
</style>
