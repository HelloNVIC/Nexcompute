<script setup lang="ts">
// 受控端环境文件管理（platform-env-ota-realtime D4）。
// 管理员上传环境文件、查看 MD5、删除、"环境网盘同步"向在线受控端广播 env.sync。
import { onMounted, ref } from 'vue'
import { message, Modal } from 'ant-design-vue'
import dayjs from 'dayjs'
import { envFileApi, type EnvFile } from '@/api/envFile'

const loading = ref(false)
const uploading = ref(false)
const uploadPercent = ref(0)
const uploadingName = ref('')
const syncing = ref(false)
const files = ref<EnvFile[]>([])

onMounted(load)

async function load(): Promise<void> {
  loading.value = true
  try {
    files.value = await envFileApi.list()
  } finally {
    loading.value = false
  }
}

async function handleUpload(file: File): Promise<boolean> {
  uploading.value = true
  uploadPercent.value = 0
  uploadingName.value = file.name
  try {
    await envFileApi.upload(file, (p) => {
      uploadPercent.value = p
    })
    message.success(`${file.name} 上传成功`)
    load()
  } catch {
    // 拦截器已提示
  } finally {
    uploading.value = false
    uploadPercent.value = 0
    uploadingName.value = ''
  }
  return false // a-upload beforeUpload 返回 false 不走默认上传
}

function confirmDelete(f: EnvFile): void {
  Modal.confirm({
    title: `确认删除环境文件「${f.filename}」？`,
    onOk: async () => {
      await envFileApi.remove(f.id)
      message.success('已删除')
      load()
    },
  })
}

async function sync(): Promise<void> {
  syncing.value = true
  try {
    const r = await envFileApi.sync()
    message.success(`已向 ${r.sent} 台在线受控端下发同步命令`)
  } finally {
    syncing.value = false
  }
}

function fmtSize(bytes: number): string {
  if (bytes >= 1024 * 1024 * 1024) return (bytes / 1024 / 1024 / 1024).toFixed(2) + ' GB'
  if (bytes >= 1024 * 1024) return (bytes / 1024 / 1024).toFixed(1) + ' MB'
  if (bytes >= 1024) return (bytes / 1024).toFixed(0) + ' KB'
  return bytes + ' B'
}
</script>

<template>
  <div>
    <div style="display: flex; justify-content: space-between; margin-bottom: 16px">
      <a-typography-title :level="3" style="margin: 0">受控端环境</a-typography-title>
      <a-space>
        <a-upload :before-upload="handleUpload" :show-upload-list="false" accept=".exe,.deb,.sh,.ps1,.zip,.tar">
          <a-button type="primary" :loading="uploading">上传环境文件</a-button>
        </a-upload>
        <a-button :loading="syncing" @click="sync">环境网盘同步</a-button>
      </a-space>
      <div v-if="uploading" style="margin-top: 12px">
        <a-typography-text type="secondary" style="font-size: 12px">正在上传 {{ uploadingName }}… {{ uploadPercent }}%</a-typography-text>
        <a-progress :percent="uploadPercent" :stroke-width="6" style="margin-top: 4px" />
      </div>
    </div>

    <a-typography-paragraph type="secondary">
      上传 Docker Desktop Installer.exe、NVIDIA Container Toolkit deb 包等；受控端按 MD5 增量同步至本地 Env 文件夹。
      "环境网盘同步"向在线受控端立即下发同步命令（离线实例下次心跳后由定时同步补齐）。
    </a-typography-paragraph>

    <a-table :data-source="files" :loading="loading" row-key="id" :pagination="false">
      <a-table-column title="文件名" data-index="filename" />
      <a-table-column title="MD5" :width="280">
        <template #default="{ record }">
          <a-typography-text code style="font-size: 12px">{{ record.md5 }}</a-typography-text>
        </template>
      </a-table-column>
      <a-table-column title="大小" :width="100">
        <template #default="{ record }">{{ fmtSize(record.size) }}</template>
      </a-table-column>
      <a-table-column title="上传人" data-index="uploadedByName" :width="100" />
      <a-table-column title="上传时间" :width="170">
        <template #default="{ record }">{{ record.uploadedAt ? dayjs(record.uploadedAt).format('YYYY-MM-DD HH:mm') : '-' }}</template>
      </a-table-column>
      <a-table-column title="操作" :width="80">
        <template #default="{ record }">
          <a-button type="link" danger size="small" @click="confirmDelete(record)">删除</a-button>
        </template>
      </a-table-column>
    </a-table>
  </div>
</template>
