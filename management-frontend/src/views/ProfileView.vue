<script setup lang="ts">
import { onMounted, reactive } from 'vue'
import { message } from 'ant-design-vue'
import { http } from '@/utils/request'
import { useAuthStore, type UserRole } from '@/stores/auth'
import type { UserInfoDto } from '@/types'

const auth = useAuthStore()

const form = reactive({
  realName: '',
  email: '',
  phone: '',
})

const roleLabel: Record<UserRole, string> = {
  ADMIN: '管理员',
  MENTOR: '导师',
  STUDENT: '学生',
}

onMounted(() => {
  if (auth.user) {
    form.realName = auth.user.realName
    form.email = auth.user.email ?? ''
    form.phone = auth.user.phone ?? ''
  }
})

async function handleSave(): Promise<void> {
  try {
    const updated = await http.put<UserInfoDto>(`/auth/me`, form)
    auth.setUser(updated)
    message.success('保存成功')
  } catch {
    // 拦截器已提示
  }
}
</script>

<template>
  <div style="max-width: 600px">
    <a-typography-title :level="3">用户信息</a-typography-title>
    <a-descriptions :column="1" bordered style="margin-bottom: 24px">
      <a-descriptions-item label="用户名">{{ auth.user?.username }}</a-descriptions-item>
      <a-descriptions-item label="角色">
        <a-tag color="blue">{{ auth.user ? roleLabel[auth.user.role] : '' }}</a-tag>
      </a-descriptions-item>
      <a-descriptions-item label="工号/学号">{{ auth.user?.studentId ?? '—' }}</a-descriptions-item>
      <a-descriptions-item label="课题组">{{ auth.user?.groupName ?? '—' }}</a-descriptions-item>
    </a-descriptions>

    <a-typography-title :level="4">编辑个人信息</a-typography-title>
    <a-form layout="vertical">
      <a-form-item label="姓名">
        <a-input v-model:value="form.realName" />
      </a-form-item>
      <a-form-item label="邮箱">
        <a-input v-model:value="form.email" />
      </a-form-item>
      <a-form-item label="手机号">
        <a-input v-model:value="form.phone" />
      </a-form-item>
      <a-button type="primary" @click="handleSave">保存</a-button>
    </a-form>
  </div>
</template>
