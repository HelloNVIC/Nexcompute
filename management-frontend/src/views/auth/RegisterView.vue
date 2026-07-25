<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { http } from '@/utils/request'

const router = useRouter()
const loading = ref(false)
const linkValid = ref<boolean | null>(null)

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

const canSubmit = computed(() =>
  form.token && form.realName && form.studentId && form.password && form.email && form.phone,
)

async function handleSubmit(): Promise<void> {
  loading.value = true
  try {
    await http.post('/auth/register', form)
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
  <div class="register-container">
    <div class="register-card">
      <h1>学生注册</h1>
      <a-alert v-if="linkValid === false" type="error" message="注册链接无效，请联系导师获取注册链接" show-icon style="margin-bottom: 16px" />
      <a-form v-else layout="vertical" @submit.prevent="handleSubmit">
        <a-form-item label="姓名" required>
          <a-input v-model:value="form.realName" placeholder="请输入真实姓名" />
        </a-form-item>
        <a-form-item label="工号/学号" required>
          <a-input v-model:value="form.studentId" placeholder="请输入工号或学号" />
        </a-form-item>
        <a-form-item label="密码" required>
          <a-input-password v-model:value="form.password" placeholder="设置登录密码" />
        </a-form-item>
        <a-form-item label="邮箱" required>
          <a-input v-model:value="form.email" placeholder="请输入邮箱" />
        </a-form-item>
        <a-form-item label="手机号" required>
          <a-input v-model:value="form.phone" placeholder="请输入手机号" />
        </a-form-item>
        <a-button type="primary" block :loading="loading" :disabled="!canSubmit" @click="handleSubmit">
          注册
        </a-button>
      </a-form>
      <div style="text-align: center; margin-top: 16px">
        <a-button type="link" @click="router.push('/login')">已有账号？去登录</a-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.register-container {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #1677ff 0%, #0958d9 100%);
}
.register-card {
  width: 440px;
  padding: 40px;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.15);
}
.register-card h1 {
  text-align: center;
  margin-bottom: 24px;
  color: #1677ff;
}
</style>
