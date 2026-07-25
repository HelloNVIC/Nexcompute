<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { message } from 'ant-design-vue'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const loading = ref(false)
const form = reactive({ username: '', password: '' })

async function handleSubmit(): Promise<void> {
  if (!form.username || !form.password) {
    message.warning('请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    await auth.login(form.username, form.password)
    message.success('登录成功')
    const redirect = (route.query.redirect as string) ?? '/dashboard'
    router.push(redirect)
  } catch {
    // request 拦截器已提示
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-container">
    <div class="login-card">
      <div class="login-header">
        <img src="/favicon.svg" class="login-logo" alt="Nexcompute" />
        <h1>合算 Nexcompute</h1>
        <p>分布式 GPU 算力管理平台</p>
      </div>
      <a-form layout="vertical" @submit.prevent="handleSubmit">
        <a-form-item label="用户名">
          <a-input v-model:value="form.username" placeholder="请输入用户名" size="large" />
        </a-form-item>
        <a-form-item label="密码">
          <a-input-password v-model:value="form.password" placeholder="请输入密码" size="large" @keyup.enter="handleSubmit" />
        </a-form-item>
        <a-button type="primary" size="large" block :loading="loading" @click="handleSubmit">
          登录
        </a-button>
      </a-form>
    </div>
  </div>
</template>

<style scoped>
.login-container {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #1677ff 0%, #0958d9 100%);
}
.login-card {
  width: 400px;
  padding: 40px;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.15);
}
.login-header {
  text-align: center;
  margin-bottom: 32px;
}
.login-logo {
  width: 64px;
  height: 64px;
  display: block;
  margin: 0 auto 16px;
}
.login-header h1 {
  font-size: 28px;
  margin: 0 0 8px;
  color: #1677ff;
}
.login-header p {
  color: #666;
  margin: 0;
}
</style>
