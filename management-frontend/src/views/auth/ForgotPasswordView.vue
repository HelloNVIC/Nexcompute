<script setup lang="ts">
import { reactive, ref, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import type { FormInstance } from 'ant-design-vue'
import { UserOutlined, LockOutlined, SafetyCertificateOutlined } from '@ant-design/icons-vue'
import { authApi } from '@/api/auth'
import AuthLayout from '@/components/AuthLayout.vue'

const router = useRouter()

const step = ref<1 | 2>(1)
const username = ref('')
const sendLoading = ref(false)
const resetLoading = ref(false)
const resetFormRef = ref<FormInstance>()
const resetForm = reactive({ code: '', newPassword: '', confirmPassword: '' })

// 60s 发送限频倒计时（与后端 send-cooldown-seconds 默认一致）
const countdown = ref(0)
let timer: ReturnType<typeof setInterval> | null = null

function startCountdown(): void {
  countdown.value = 60
  timer = setInterval(() => {
    countdown.value--
    if (countdown.value <= 0) {
      clearTimer()
    }
  }, 1000)
}

function clearTimer(): void {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

onUnmounted(() => clearTimer())

async function handleSendCode(): Promise<void> {
  if (!username.value) {
    message.warning('请输入工号/学号')
    return
  }
  if (countdown.value > 0) return
  sendLoading.value = true
  try {
    const res = await authApi.sendResetCode(username.value)
    message.success(res.message)
    startCountdown()
    step.value = 2
  } catch {
    // 失败由 request 拦截器提示；不进入 step2
  } finally {
    sendLoading.value = false
  }
}

const resetRules = {
  code: [{ required: true, message: '请输入验证码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请设置新密码', trigger: 'blur' },
    { min: 6, message: '密码至少 6 位', trigger: 'blur' },
    {
      validator: (_rule: unknown, value: string) =>
        /^(?=.*[0-9])(?=.*[a-zA-Z]).{6,}$/.test(value)
          ? Promise.resolve()
          : Promise.reject('密码需包含数字和字母'),
      trigger: 'blur',
    },
  ],
  confirmPassword: [
    { required: true, message: '请确认新密码', trigger: 'blur' },
    {
      validator: (_rule: unknown, value: string) =>
        !value || value === resetForm.newPassword
          ? Promise.resolve()
          : Promise.reject('两次输入的新密码不一致'),
      trigger: 'blur',
    },
  ],
}

async function handleReset(): Promise<void> {
  try {
    await resetFormRef.value?.validate()
  } catch {
    return
  }
  resetLoading.value = true
  try {
    await authApi.resetPassword(username.value, resetForm.code, resetForm.newPassword)
    message.success('密码重置成功，请使用新密码登录')
    clearTimer()
    router.push('/login')
  } catch {
    // 失败由拦截器提示（验证码错误/过期/已用尽等）
  } finally {
    resetLoading.value = false
  }
}
</script>

<template>
  <AuthLayout title="忘记密码">
    <a-alert
      v-if="step === 2"
      type="info"
      message="若账号存在，验证码已发送至其绑定邮箱"
      show-icon
      style="margin-bottom: 16px"
    />
    <a-form v-if="step === 1" layout="vertical" @submit.prevent="handleSendCode">
      <a-form-item label="工号/学号" required>
        <a-input v-model:value="username" placeholder="请输入工号/学号" size="large">
          <template #prefix><UserOutlined /></template>
        </a-input>
      </a-form-item>
      <a-button
        type="primary"
        size="large"
        block
        :loading="sendLoading"
        :disabled="countdown > 0"
        @click="handleSendCode"
      >
        {{ countdown > 0 ? `重新发送(${countdown}s)` : '发送验证码' }}
      </a-button>
    </a-form>
    <a-form
      v-else
      ref="resetFormRef"
      :model="resetForm"
      :rules="resetRules"
      layout="vertical"
      @submit.prevent="handleReset"
    >
      <a-form-item label="验证码" name="code">
        <a-input v-model:value="resetForm.code" placeholder="请输入 6 位验证码" size="large">
          <template #prefix><SafetyCertificateOutlined /></template>
        </a-input>
      </a-form-item>
      <a-form-item label="新密码" name="newPassword">
        <a-input-password
          v-model:value="resetForm.newPassword"
          placeholder="至少 6 位，含数字和字母"
          size="large"
        >
          <template #prefix><LockOutlined /></template>
        </a-input-password>
      </a-form-item>
      <a-form-item label="确认新密码" name="confirmPassword">
        <a-input-password
          v-model:value="resetForm.confirmPassword"
          placeholder="请再次输入新密码"
          size="large"
        >
          <template #prefix><LockOutlined /></template>
        </a-input-password>
      </a-form-item>
      <a-button type="primary" size="large" block :loading="resetLoading" @click="handleReset">
        重置密码
      </a-button>
      <div style="text-align: center; margin-top: 12px">
        <a-button type="link" :disabled="countdown > 0 || sendLoading" @click="handleSendCode">
          {{ countdown > 0 ? `重新发送验证码(${countdown}s)` : '重新发送验证码' }}
        </a-button>
      </div>
    </a-form>
    <div style="text-align: center; margin-top: 16px">
      <a-button type="link" @click="router.push('/login')">返回登录</a-button>
    </div>
  </AuthLayout>
</template>
