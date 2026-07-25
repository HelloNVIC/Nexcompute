<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { localAdminPasswordApi } from '@/api/user'

const submitting = ref(false)
const alreadySet = ref(false)
// 表单是否可编辑（已设置时需先确认"是否修改"才放开，platform-refinements #5）
const formEnabled = ref(false)
const form = reactive({ password: '', confirm: '' })

onMounted(async () => {
  try {
    const st = await localAdminPasswordApi.status()
    alreadySet.value = st.set
    if (!st.set) {
      // 未设置，直接允许设置
      formEnabled.value = true
    }
    // 已设置则保持锁定，等用户点击"修改"并确认
  } catch {
    formEnabled.value = true
  }
})

function askModify(): void {
  Modal.confirm({
    title: '受控端管理密码已设置',
    content: '是否修改当前全局密码？修改后将重新下发至所有已连接受控端。',
    onOk: () => {
      formEnabled.value = true
    },
  })
}

async function handleSubmit(): Promise<void> {
  if (!formEnabled.value) {
    message.warning('请先确认是否修改')
    return
  }
  if (!form.password) {
    message.warning('请输入密码')
    return
  }
  if (form.password !== form.confirm) {
    message.warning('两次输入的密码不一致')
    return
  }
  submitting.value = true
  try {
    await localAdminPasswordApi.set(form.password)
    message.success('全局受控端管理密码已设置并下发至所有已连接受控端')
    alreadySet.value = true
    formEnabled.value = false
    form.password = ''
    form.confirm = ''
  } catch {
    // 拦截器已提示
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <div>
    <a-typography-title :level="3">受控端管理密码</a-typography-title>
    <a-alert
      :type="alreadySet ? 'success' : 'warning'"
      show-icon
      :message="alreadySet ? '全局密码已设置' : '全局密码未设置'"
      description="所有受控端共用同一个管理员密码，设置后立即下发至所有已连接受控端（明文保存）。离线受控端上线后经心跳自动同步当前密码。"
      style="margin-bottom: 24px; max-width: 640px"
    />
    <a-card title="设置全局密码" style="max-width: 640px">
      <a-form layout="vertical">
        <a-form-item label="新密码" required>
          <a-input-password
            v-model:value="form.password"
            :disabled="!formEnabled"
            placeholder="输入受控端管理密码"
          />
        </a-form-item>
        <a-form-item label="确认密码" required>
          <a-input-password
            v-model:value="form.confirm"
            :disabled="!formEnabled"
            placeholder="再次输入密码"
          />
        </a-form-item>
        <a-form-item>
          <a-space>
            <a-button
              v-if="alreadySet && !formEnabled"
              @click="askModify"
            >修改</a-button>
            <a-button
              v-else
              type="primary"
              :loading="submitting"
              :disabled="!formEnabled"
              @click="handleSubmit"
            >设置并下发</a-button>
          </a-space>
        </a-form-item>
      </a-form>
      <a-typography-text type="secondary" style="font-size: 12px">
        该密码保护受控端退出与修改存储池根目录等受保护操作。
      </a-typography-text>
    </a-card>
  </div>
</template>
