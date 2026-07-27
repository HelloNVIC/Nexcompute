<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import { http } from '@/utils/request'
import { useAuthStore, type UserRole } from '@/stores/auth'
import type { UserInfoDto } from '@/types'
import { emailApi, type EmailPref } from '@/api/email'

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

// 邮件偏好
const prefs = ref<EmailPref[]>([])
const prefsLoading = ref(false)
const prefsSaving = ref(false)

onMounted(() => {
  if (auth.user) {
    form.realName = auth.user.realName
    form.email = auth.user.email ?? ''
    form.phone = auth.user.phone ?? ''
  }
  loadPrefs()
})

async function loadPrefs(): Promise<void> {
  prefsLoading.value = true
  try {
    prefs.value = await emailApi.getPrefs()
  } catch {
    // 拦截器已提示
  } finally {
    prefsLoading.value = false
  }
}

async function togglePref(key: string, checked: boolean): Promise<void> {
  prefsSaving.value = true
  try {
    const map: Record<string, boolean> = { [key]: checked }
    prefs.value = await emailApi.updatePrefs(map)
    message.success('偏好已更新')
  } catch {
    // 拦截器已提示，回滚
    await loadPrefs()
  } finally {
    prefsSaving.value = false
  }
}

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
  <div class="profile-view">
    <a-typography-title :level="3">用户信息</a-typography-title>
    <div class="cards-grid">
      <a-card title="基本信息">
        <a-descriptions :column="1" bordered>
          <a-descriptions-item label="用户名">{{ auth.user?.username }}</a-descriptions-item>
          <a-descriptions-item label="角色">
            <a-tag color="blue">{{ auth.user ? roleLabel[auth.user.role] : '' }}</a-tag>
          </a-descriptions-item>
          <a-descriptions-item label="工号/学号">{{ auth.user?.studentId ?? '-' }}</a-descriptions-item>
          <a-descriptions-item label="课题组">{{ auth.user?.groupName ?? '-' }}</a-descriptions-item>
        </a-descriptions>
      </a-card>

      <a-card title="编辑个人信息">
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
      </a-card>

      <a-card title="邮件提醒偏好" class="span-full" :loading="prefsLoading">
        <p style="color: #8c8c8c">
          可逐项关闭非强制提醒；"用户注册""用户账户被禁用""用户账户已启用"为强制提醒，不可关闭。
        </p>
        <a-list :data-source="prefs" :loading="prefsSaving" item-layout="horizontal">
          <template #renderItem="{ item }">
            <a-list-item>
              <a-list-item-meta :description="item.mandatory ? '强制提醒，不可关闭' : '可关闭'">
                <template #title>{{ item.description }}</template>
              </a-list-item-meta>
              <template #actions>
                <a-switch
                  :checked="item.enabled"
                  :disabled="item.mandatory"
                  @change="(c: boolean) => togglePref(item.triggerKey, c)"
                />
              </template>
            </a-list-item>
          </template>
        </a-list>
      </a-card>
    </div>
  </div>
</template>

<style scoped>
.profile-view {
  max-width: 1080px;
  margin: 0 auto;
}
/* 双排居中：基本信息 + 编辑个人信息 两列；邮件提醒偏好 跨整行 */
.cards-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
  align-items: start;
}
.span-full {
  grid-column: 1 / -1;
}
@media (max-width: 900px) {
  .cards-grid {
    grid-template-columns: 1fr;
  }
}
</style>
