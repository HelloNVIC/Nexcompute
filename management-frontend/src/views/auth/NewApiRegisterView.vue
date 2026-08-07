<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import type { FormInstance } from 'ant-design-vue'
import { UserOutlined, LockOutlined, MailOutlined, PhoneOutlined, IdcardOutlined, CheckCircleOutlined, CloseCircleOutlined, LoadingOutlined } from '@ant-design/icons-vue'
import { newApiUserAllocationApi, type NewApiRegisterFormMeta, type NewApiUsernameCheckCode } from '@/api/newApiUserAllocation'
import AuthLayout from '@/components/AuthLayout.vue'

const loading = ref(false)
const submitting = ref(false)
// null=校验中；true=有效；false=无效；'submitted'=已提交待审批
const linkState = ref<boolean | null | 'submitted'>(null)
const errorMsg = ref('')
const meta = ref<NewApiRegisterFormMeta | null>(null)
const submittedId = ref<number | null>(null)
const submittedPortalUrl = ref<string>('')
const formRef = ref<FormInstance>()

const form = reactive({
  token: '',
  username: '',
  displayName: '',
  email: '',
  phone: '',
  password: '',
})

// 用户名可用性检查
const usernameChecking = ref(false)
const usernameCheck = ref<{ available: boolean; code: NewApiUsernameCheckCode; message: string } | null>(null)

const rules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    {
      validator: (_rule: unknown, value: string) =>
        /^[A-Za-z][A-Za-z0-9_]{2,63}$/.test(value)
          ? Promise.resolve()
          : Promise.reject('用户名须以字母开头，仅含字母/数字/下划线，3-64 字符'),
      trigger: 'blur',
    },
  ],
  displayName: [{ required: true, message: '请输入显示名', trigger: 'blur' }],
  email: [
    { required: true, message: '请输入邮箱', trigger: 'blur' },
    { type: 'email', message: '邮箱格式不正确', trigger: 'blur' },
  ],
  phone: [
    { required: true, message: '请输入手机号', trigger: 'blur' },
    { pattern: /^[0-9+][0-9 +()\-]{4,19}$/, message: '手机号格式不正确', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请设置登录密码', trigger: 'blur' },
    { min: 8, message: '密码至少 8 位', trigger: 'blur' },
    {
      validator: (_rule: unknown, value: string) =>
        /^(?=.*[0-9])(?=.*[a-zA-Z]).{8,}$/.test(value)
          ? Promise.resolve()
          : Promise.reject('密码需同时包含字母和数字'),
      trigger: 'blur',
    },
  ],
}

onMounted(() => {
  const params = new URLSearchParams(window.location.search)
  form.token = params.get('token') ?? ''
  if (!form.token) {
    linkState.value = false
    errorMsg.value = '注册链接缺少令牌'
    return
  }
  validateToken()
})

async function validateToken(): Promise<void> {
  loading.value = true
  try {
    meta.value = await newApiUserAllocationApi.getRegisterForm(form.token)
    linkState.value = true
  } catch (e) {
    linkState.value = false
    errorMsg.value = (e as Error)?.message || '邀请令牌无效或已失效'
  } finally {
    loading.value = false
  }
}

// 用户名 onBlur 实时检查 NewAPI 系统 + 本地占用
async function onUsernameBlur(): Promise<void> {
  const username = form.username.trim()
  usernameCheck.value = null
  if (!username || !/^[A-Za-z][A-Za-z0-9_]{2,63}$/.test(username)) return
  usernameChecking.value = true
  try {
    usernameCheck.value = await newApiUserAllocationApi.checkUsername(username)
  } catch {
    usernameCheck.value = { available: false, code: 'ERROR', message: '检查失败，请重试' }
  } finally {
    usernameChecking.value = false
  }
}

async function handleSubmit(): Promise<void> {
  try {
    await formRef.value?.validate()
  } catch {
    return
  }
  submitting.value = true
  try {
    const res = await newApiUserAllocationApi.submitRegister({ ...form })
    submittedId.value = res.registrationId
    submittedPortalUrl.value = res.newapiPortalUrl || ''
    linkState.value = 'submitted'
    message.success('申请已提交')
  } catch {
    // request 拦截器已提示
  } finally {
    submitting.value = false
  }
}

function goClose(): void {
  // window.close() 仅对脚本打开的窗口生效；非脚本打开时提示用户手动关闭
  window.close()
  setTimeout(() => message.info('可以手动关闭此页面'), 300)
}

function goNewApiLogin(): void {
  const url = submittedPortalUrl.value
  if (url) {
    window.location.href = url
  } else {
    message.warning('未获取到 NewAPI 登录地址')
  }
}
</script>

<template>
  <AuthLayout title="Token 用户注册">
    <a-spin :spinning="loading">
      <!-- 校验中 -->
      <a-alert v-if="linkState === null" type="info" message="正在校验邀请令牌..." show-icon />

      <!-- 令牌无效 -->
      <a-alert
        v-else-if="linkState === false"
        type="error"
        message="注册链接无效"
        :description="errorMsg"
        show-icon
      >
        <template #action>
          <a-button type="link" @click="$router.push('/login')">返回登录</a-button>
        </template>
      </a-alert>

      <!-- 已提交待审批 -->
      <a-result
        v-else-if="linkState === 'submitted'"
        status="success"
        title="申请已提交，待审批"
        sub-title="管理员审批通过后将为你开通 NewAPI（Token）账号，激活后你的注册邮箱会收到通知邮件。"
      >
        <template #extra>
          <div style="display: flex; gap: 24px; justify-content: center">
            <a-button type="primary" @click="goClose">好的</a-button>
            <a-button @click="goNewApiLogin">进入 NewAPI 登录界面</a-button>
          </div>
        </template>
      </a-result>

      <!-- 注册表单 -->
      <div v-else-if="linkState === true && meta">
        <a-alert
          type="info" show-icon style="margin-bottom: 16px"
          :message="`邀请：${meta.label}`"
          :description="`剩余名额 ${meta.remaining} 个，过期时间 ${new Date(meta.expiresAt).toLocaleString()}`"
        />
        <a-form ref="formRef" :model="form" :rules="rules" layout="vertical" @submit.prevent="handleSubmit">
          <a-form-item label="NewAPI 用户名" name="username">
            <a-input v-model:value="form.username" placeholder="以字母开头，含字母/数字/下划线，3-64 字符" size="large" @blur="onUsernameBlur">
              <template #prefix><IdcardOutlined /></template>
            </a-input>
            <div v-if="usernameChecking || usernameCheck" style="margin-top: 4px; font-size: 12px">
              <span v-if="usernameChecking" style="color: #888"><LoadingOutlined /> 正在检查 NewAPI 系统是否可用...</span>
              <span v-else-if="usernameCheck?.available" style="color: #52c41a"><CheckCircleOutlined /> {{ usernameCheck.message }}</span>
              <span v-else style="color: #ff4d4f"><CloseCircleOutlined /> {{ usernameCheck?.message }}</span>
            </div>
          </a-form-item>
          <a-form-item label="显示名" name="displayName">
            <a-input v-model:value="form.displayName" placeholder="请输入显示名（将作为 NewAPI 显示名）" size="large">
              <template #prefix><UserOutlined /></template>
            </a-input>
          </a-form-item>
          <a-form-item label="邮箱" name="email">
            <a-input v-model:value="form.email" placeholder="请输入邮箱（开通后通知到该邮箱）" size="large">
              <template #prefix><MailOutlined /></template>
            </a-input>
          </a-form-item>
          <a-form-item label="手机号" name="phone">
            <a-input v-model:value="form.phone" placeholder="请输入手机号" size="large">
              <template #prefix><PhoneOutlined /></template>
            </a-input>
          </a-form-item>
          <a-form-item label="登录密码" name="password">
            <a-input-password v-model:value="form.password" placeholder="至少 8 位，含字母和数字" size="large">
              <template #prefix><LockOutlined /></template>
            </a-input-password>
          </a-form-item>
          <a-button type="primary" size="large" block :loading="submitting" @click="handleSubmit">
            提交申请
          </a-button>
        </a-form>
      </div>
    </a-spin>
  </AuthLayout>
</template>
