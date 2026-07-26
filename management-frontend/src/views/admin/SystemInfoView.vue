<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import { http } from '@/utils/request'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const isOwner = auth.role === 'ADMIN'
const loading = ref(false)
const saving = ref(false)
const form = reactive({ maintainer: '', maintainerPhone: '', owner: '', ownerPhone: '' })

onMounted(async () => {
  loading.value = true
  try {
    const info = await http.get<Record<string, string>>('/system-info')
    form.maintainer = info.maintainer || ''
    form.maintainerPhone = info.maintainerPhone || ''
    form.owner = info.owner || ''
    form.ownerPhone = info.ownerPhone || ''
  } finally {
    loading.value = false
  }
})

async function save(): Promise<void> {
  saving.value = true
  try {
    await http.put('/system-info', form)
    message.success('系统信息已保存')
  } catch {
    // 拦截器已提示
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div>
    <a-typography-title :level="3">系统信息</a-typography-title>
    <a-card :loading="loading" style="max-width: 640px">
      <a-form layout="vertical">
        <a-form-item label="维护人">
          <a-input v-model:value="form.maintainer" :disabled="!isOwner" placeholder="维护人姓名" />
        </a-form-item>
        <a-form-item label="维护电话">
          <a-input v-model:value="form.maintainerPhone" :disabled="!isOwner" placeholder="维护人联系电话" />
        </a-form-item>
        <a-form-item label="责任人">
          <a-input v-model:value="form.owner" :disabled="!isOwner" placeholder="责任人姓名" />
        </a-form-item>
        <a-form-item label="责任人电话">
          <a-input v-model:value="form.ownerPhone" :disabled="!isOwner" placeholder="责任人联系电话" />
        </a-form-item>
        <a-form-item v-if="isOwner">
          <a-button type="primary" :loading="saving" @click="save">保存</a-button>
        </a-form-item>
      </a-form>
    </a-card>
  </div>
</template>
