<script setup lang="ts">
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { extractApiError } from '@/api/http'
import type { LoginPayload } from '@/api/auth'

const router = useRouter()
const authStore = useAuthStore()

const form = reactive<LoginPayload>({
  loginId: '',
  password: '',
})

const loading = ref(false)
const errorMsg = ref('')

async function handleLogin() {
  if (!form.loginId.trim() || !form.password.trim()) {
    errorMsg.value = '请输入登录标识和密码'
    return
  }
  errorMsg.value = ''
  loading.value = true
  try {
    await authStore.login({
      loginId: form.loginId.trim(),
      password: form.password,
    })
    router.push(authStore.resolveLandingPath())
  } catch (err) {
    errorMsg.value = extractApiError(err, '登录失败')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="login-card">
      <a href="/" class="login-logo">
        <svg width="36" height="36" viewBox="0 0 32 32" fill="none">
          <defs>
            <linearGradient id="loginPageGrad" x1="2" y1="2" x2="30" y2="30"><stop stop-color="#4F46E5"/><stop offset="1" stop-color="#22D3EE"/></linearGradient>
          </defs>
          <circle cx="16" cy="11" r="7.5" fill="url(#loginPageGrad)" opacity="0.12"/>
          <circle cx="16" cy="11" r="4.6" fill="url(#loginPageGrad)" opacity="0.3"/>
          <circle cx="16" cy="11" r="2.4" fill="url(#loginPageGrad)"/>
          <path d="M13.2 28 L18.8 28 L17.2 15.8 L14.8 15.8 Z" fill="url(#loginPageGrad)"/>
        </svg>
        <span>Pharos 知识库</span>
      </a>

      <h1>登录</h1>
      <p class="subtitle">使用您的账号登录Pharos 知识库</p>

      <form class="login-form" @submit.prevent="handleLogin">
        <div class="input-group">
          <label>登录标识</label>
          <input
            v-model="form.loginId"
            type="text"
            placeholder="用户名或邮箱"
            autocomplete="username"
          />
        </div>

        <div class="input-group">
          <label>密码</label>
          <input
            v-model="form.password"
            type="password"
            placeholder="输入密码"
            autocomplete="current-password"
          />
        </div>

        <p v-if="errorMsg" class="form-error">{{ errorMsg }}</p>

        <button type="submit" class="btn-login" :disabled="loading">
          <span v-if="!loading">登录</span>
          <span v-else>登录中...</span>
        </button>
      </form>

      <p class="form-footer">
        还没有账号？<a href="/">返回首页注册</a>
      </p>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(180deg, #f0f6fc 0%, #f5f7fd 60%, #ffffff 100%);
  padding: 40px 24px;
}

.login-card {
  width: 100%;
  max-width: 420px;
  background: var(--surface-white);
  border-radius: var(--radius-lg);
  padding: 44px 40px;
  box-shadow: var(--shadow-xl);
  border: 1px solid var(--border-default);
}

.login-logo {
  display: flex;
  align-items: center;
  gap: 10px;
  font-family: 'Poppins', 'Noto Sans SC', sans-serif;
  font-weight: 700;
  font-size: 17px;
  color: var(--text-primary);
  margin-bottom: 28px;
}

.login-card h1 {
  font-family: 'Poppins', 'Noto Sans SC', sans-serif;
  font-size: 24px;
  font-weight: 800;
  color: var(--text-primary);
  margin-bottom: 6px;
}

.subtitle {
  font-size: 14px;
  color: var(--text-secondary);
  margin-bottom: 28px;
}

.login-form {
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.input-group {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.input-group label {
  font-size: 13px;
  font-weight: 600;
  color: var(--text-primary);
}

.input-group input {
  padding: 11px 14px;
  border: 1.5px solid var(--border-default);
  border-radius: var(--radius-sm);
  font-size: 14px;
  font-family: inherit;
  color: var(--text-primary);
  background: var(--surface-white);
  transition: border-color 0.2s ease, box-shadow 0.2s ease;
  outline: none;
}

.input-group input::placeholder {
  color: #94a3b8;
}

.input-group input:focus {
  border-color: var(--brand-primary);
  box-shadow: 0 0 0 3px rgba(79, 70, 229, 0.1);
}

.form-error {
  font-size: 13px;
  color: var(--el-color-danger);
  margin: 0;
}

.btn-login {
  padding: 12px 24px;
  border-radius: var(--radius-sm);
  background: linear-gradient(135deg, var(--brand-primary), var(--brand-primary-dark));
  color: #fff;
  font-size: 15px;
  font-weight: 600;
  transition: all 0.2s ease;
  box-shadow: 0 4px 14px rgba(79, 70, 229, 0.25);
  margin-top: 4px;
}

.btn-login:hover:not(:disabled) {
  box-shadow: 0 6px 20px rgba(79, 70, 229, 0.35);
  transform: translateY(-1px);
}

.btn-login:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.form-footer {
  margin-top: 24px;
  text-align: center;
  font-size: 14px;
  color: var(--text-secondary);
}

.form-footer a {
  color: var(--brand-primary);
  font-weight: 600;
}

.form-footer a:hover {
  text-decoration: underline;
}
</style>
