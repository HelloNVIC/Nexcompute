<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { message } from 'ant-design-vue'
import { UserOutlined, LockOutlined } from '@ant-design/icons-vue'
import { useAuthStore } from '@/stores/auth'
import AuthLayout from '@/components/AuthLayout.vue'

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
    // 失败原因（密码错误/账号不存在/账号禁用）由 request 拦截器按后端 code 提示
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <AuthLayout title="欢迎登录">
    <a-form layout="vertical" @submit.prevent="handleSubmit">
      <a-form-item label="用户名">
        <a-input v-model:value="form.username" placeholder="请输入用户名" size="large">
          <template #prefix><UserOutlined /></template>
        </a-input>
      </a-form-item>
      <a-form-item label="密码">
        <a-input-password
          v-model:value="form.password"
          placeholder="请输入密码"
          size="large"
          @keyup.enter="handleSubmit"
        >
          <template #prefix><LockOutlined /></template>
        </a-input-password>
      </a-form-item>
      <a-button type="primary" size="large" block :loading="loading" @click="handleSubmit">
        登录
      </a-button>
    </a-form>
  </AuthLayout>
</template>
