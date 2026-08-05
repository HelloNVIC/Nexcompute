<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import type { FormInstance } from 'ant-design-vue'
import { UserOutlined, LockOutlined, MailOutlined, PhoneOutlined, IdcardOutlined } from '@ant-design/icons-vue'
import { http } from '@/utils/request'
import AuthLayout from '@/components/AuthLayout.vue'

const router = useRouter()
const loading = ref(false)
const linkValid = ref<boolean | null>(null)
const formRef = ref<FormInstance>()

// V31：管理员注册表单复用学生注册字段（姓名/工号/密码/邮箱/手机）
const form = reactive({
  token: '',
  realName: '',
  studentId: '',
  password: '',
  email: '',
  phone: '',
})

onMounted(() => {
  const params = new URLSearchParams(window.location.search)
  form.token = params.get('token') ?? ''
  if (!form.token) {
    linkValid.value = false
  }
})

const rules = {
  realName: [{ required: true, message: '请输入真实姓名', trigger: 'blur' }],
  studentId: [{ required: true, message: '请输入工号或学号', trigger: 'blur' }],
  password: [
    { required: true, message: '请设置登录密码', trigger: 'blur' },
    { min: 6, message: '密码至少 6 位', trigger: 'blur' },
    {
      validator: (_rule: unknown, value: string) =>
        /^(?=.*[0-9])(?=.*[a-zA-Z]).{6,}$/.test(value)
          ? Promise.resolve()
          : Promise.reject('密码需包含数字和字母'),
      trigger: 'blur',
    },
  ],
  email: [
    { required: true, message: '请输入邮箱', trigger: 'blur' },
    { type: 'email', message: '邮箱格式不正确', trigger: 'blur' },
  ],
  phone: [
    { required: true, message: '请输入手机号', trigger: 'blur' },
    { pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' },
  ],
}

async function handleSubmit(): Promise<void> {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  loading.value = true
  try {
    await http.post('/auth/admin-register', form)
    message.success('注册成功，请登录')
    router.push('/login')
  } catch {
    // request 拦截器已提示
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <AuthLayout title="管理员注册">
    <a-alert
      v-if="linkValid === false"
      type="error"
      message="注册链接无效"
      description="请向管理员获取有效的管理员邀请链接后再次打开。"
      show-icon
      style="margin-bottom: 16px"
    />
    <a-form v-else ref="formRef" :model="form" :rules="rules" layout="vertical" @submit.prevent="handleSubmit">
      <a-form-item label="姓名" name="realName">
        <a-input v-model:value="form.realName" placeholder="请输入真实姓名" size="large">
          <template #prefix><UserOutlined /></template>
        </a-input>
      </a-form-item>
      <a-form-item label="工号/学号" name="studentId">
        <a-input v-model:value="form.studentId" placeholder="请输入工号或学号" size="large">
          <template #prefix><IdcardOutlined /></template>
        </a-input>
      </a-form-item>
      <a-form-item label="密码" name="password">
        <a-input-password v-model:value="form.password" placeholder="至少 6 位，含数字和字母" size="large">
          <template #prefix><LockOutlined /></template>
        </a-input-password>
      </a-form-item>
      <a-form-item label="邮箱" name="email">
        <a-input v-model:value="form.email" placeholder="请输入邮箱" size="large">
          <template #prefix><MailOutlined /></template>
        </a-input>
      </a-form-item>
      <a-form-item label="手机号" name="phone">
        <a-input v-model:value="form.phone" placeholder="请输入手机号" size="large">
          <template #prefix><PhoneOutlined /></template>
        </a-input>
      </a-form-item>
      <a-button type="primary" size="large" block :loading="loading" @click="handleSubmit">
        完成注册
      </a-button>
    </a-form>
    <div style="text-align: center; margin-top: 16px">
      <a-button type="link" @click="router.push('/login')">已有账号？去登录</a-button>
    </div>
  </AuthLayout>
</template>
