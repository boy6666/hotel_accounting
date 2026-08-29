<script setup>
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const username = ref('')
const password = ref('')
const loading = ref(false)
const error = ref('')

async function submit() {
  if (!username.value || !password.value) {
    error.value = '请输入用户名和密码'
    return
  }
  loading.value = true
  error.value = ''
  try {
    await auth.login(username.value, password.value)
    router.push(route.query.redirect ? String(route.query.redirect) : '/')
  } catch (e) {
    // 网络/超时：映射中文提示，不暴露原始英文
    if (e && (e.code === 'NETWORK' || e.code === 'ECONNABORTED')) {
      error.value = '网络异常，请检查后端服务'
    } else {
      error.value = (e && e.message) || '登录失败，请重试'
    }
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-bg">
    <div class="login-card">
      <div class="login-brand">捌宿轻居</div>
      <div class="login-sub">记账 · 经营分析 · AI 定价</div>
      <form class="field" @submit.prevent="submit">
        <input v-model="username" placeholder="用户名" autocomplete="username" />
        <input v-model="password" type="password" placeholder="密码" autocomplete="current-password" />
        <p v-if="error" class="login-error">{{ error }}</p>
        <button class="btn btn-primary btn-block" type="submit" :disabled="loading">
          {{ loading ? '登录中…' : '登 录' }}
        </button>
      </form>
    </div>
  </div>
</template>
