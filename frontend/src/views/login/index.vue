<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useToastStore } from '@/stores/toast'

/**
 * 登录页（T01 占位实现）。
 *
 * 已接入 axios 单例与 auth store，可对真实 `/api/auth/login` 发起请求；
 * 视觉细化与验证码等增强留待 T05。
 */
const auth = useAuthStore()
const toast = useToastStore()
const router = useRouter()
const route = useRoute()

const form = reactive({ username: '', password: '' })
const submitting = ref(false)

async function onSubmit(): Promise<void> {
  if (!form.username || !form.password) {
    toast.warning('请输入用户名与密码')
    return
  }
  submitting.value = true
  try {
    await auth.login({ username: form.username, password: form.password })
    // 拉取角色与权限（主布局/守卫依赖），失败不阻断登录后的跳转
    try {
      await auth.fetchInfo()
    } catch {
      // 忽略：进入页面后主布局会再次尝试拉取
    }
    // 保留 redirect 回跳：登录成功后返回进入登录页前的目标页面（G-24）
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/dashboard'
    await router.replace(redirect)
  } catch (error) {
    const message = error instanceof Error ? error.message : '登录失败，请稍后再试'
    toast.error(message)
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div class="login">
    <form
      class="login__card"
      @submit.prevent="onSubmit"
    >
      <h1 class="login__title">
        衔光管家 · 管理后台
      </h1>
      <label class="login__field">
        <span>用户名</span>
        <input
          v-model="form.username"
          type="text"
          autocomplete="username"
          placeholder="请输入用户名"
        >
      </label>
      <label class="login__field">
        <span>密码</span>
        <input
          v-model="form.password"
          type="password"
          autocomplete="current-password"
          placeholder="请输入密码"
        >
      </label>
      <button
        class="login__submit"
        type="submit"
        :disabled="submitting"
      >
        {{ submitting ? '登录中…' : '登录' }}
      </button>
    </form>
  </div>
</template>

<style scoped>
.login {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  background: #f0f2f5;
}

.login__card {
  width: 320px;
  padding: 32px;
  background: #fff;
  border-radius: 8px;
  box-shadow: 0 4px 16px rgb(0 0 0 / 8%);
}

.login__title {
  margin: 0 0 24px;
  font-size: 20px;
  text-align: center;
}

.login__field {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 16px;
  font-size: 14px;
}

.login__field input {
  padding: 8px 12px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
}

.login__submit {
  width: 100%;
  padding: 10px;
  color: #fff;
  cursor: pointer;
  background: #409eff;
  border: none;
  border-radius: 4px;
}

.login__submit:disabled {
  cursor: not-allowed;
  opacity: 0.6;
}
</style>
