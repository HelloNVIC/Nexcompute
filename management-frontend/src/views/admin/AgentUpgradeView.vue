<script setup lang="ts">
// 受控端 OTA 升级（platform-env-ota-realtime D7）。
// 管理员上传新版 exe（版本号填后方可上传）、查看/删除已有版本、选择实例批量/单独升级、查看升级任务状态。
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { message, Modal } from 'ant-design-vue'
import dayjs from 'dayjs'
import { agentUpgradeApi, type AgentVersion, type AgentUpgradeTask } from '@/api/agentUpgrade'
import { instanceApi, type PhysicalInstance } from '@/api/instance'

const loadingVersions = ref(false)
const loadingTasks = ref(false)
const uploading = ref(false)
const uploadPercent = ref(0)
const upgrading = ref(false)
const versions = ref<AgentVersion[]>([])
const tasks = ref<AgentUpgradeTask[]>([])
const instances = ref<PhysicalInstance[]>([])

const selectedInstanceIds = ref<number[]>([])
const selectedVersion = ref<string | undefined>()
const uploadVersion = ref('')

const canUpload = computed(() => uploadVersion.value.trim().length > 0)

// 物理实例（含当前版本/在线状态）与升级任务自动刷新：升级过程中版本会变化，
// 定时刷新让管理员看到受控端重启后回传的新版本与任务状态变化。
let refreshTimer: ReturnType<typeof setInterval> | null = null

onMounted(async () => {
  await Promise.all([loadVersions(), loadTasks(), loadInstances()])
  refreshTimer = setInterval(async () => {
    // 静默刷新（不触发 loading 闪烁），升级进行中或刚完成时持续更新
    try {
      await Promise.all([loadInstancesSilent(), loadTasksSilent()])
    } catch {
      // 拦截器已提示
    }
  }, 5000)
})

onUnmounted(() => {
  if (refreshTimer) {
    clearInterval(refreshTimer)
    refreshTimer = null
  }
})

async function loadVersions(): Promise<void> {
  loadingVersions.value = true
  try {
    versions.value = await agentUpgradeApi.versions()
  } finally {
    loadingVersions.value = false
  }
}

async function loadTasks(): Promise<void> {
  loadingTasks.value = true
  try {
    tasks.value = await agentUpgradeApi.tasks()
  } finally {
    loadingTasks.value = false
  }
}

// 静默刷新（不触发 loading 闪烁），供定时器调用
async function loadTasksSilent(): Promise<void> {
  tasks.value = await agentUpgradeApi.tasks()
}

async function loadInstances(): Promise<void> {
  instances.value = await instanceApi.list()
  pruneOfflineSelection()
}

async function loadInstancesSilent(): Promise<void> {
  instances.value = await instanceApi.list()
  pruneOfflineSelection()
}

// 离线实例不可升级：剔除已勾选中转为离线的实例（5s 静默刷新期间状态可能变化）
function pruneOfflineSelection(): void {
  const online = new Set(instances.value.filter((i) => i.status === 'ONLINE').map((i) => i.id))
  selectedInstanceIds.value = selectedInstanceIds.value.filter((id) => online.has(id))
}

// 语义化版本比较（按 . 分段数值比，缺失段按 0；空/非数字段不炸）
function compareVersions(a?: string, b?: string): number {
  const pa = (a || '').split('.').map((n) => parseInt(n, 10) || 0)
  const pb = (b || '').split('.').map((n) => parseInt(n, 10) || 0)
  const len = Math.max(pa.length, pb.length)
  for (let i = 0; i < len; i++) {
    const d = (pa[i] ?? 0) - (pb[i] ?? 0)
    if (d !== 0) return d
  }
  return 0
}

async function handleUpload(file: File): Promise<boolean> {
  if (!canUpload.value) {
    message.warning('请先填写版本号')
    return false
  }
  uploading.value = true
  uploadPercent.value = 0
  try {
    await agentUpgradeApi.upload(file, uploadVersion.value.trim(), (p) => {
      uploadPercent.value = p
    })
    message.success(`${uploadVersion.value} 上传成功`)
    uploadVersion.value = ''
    await loadVersions()
  } finally {
    uploading.value = false
    uploadPercent.value = 0
  }
  return false
}

function confirmDeleteVersion(v: AgentVersion): void {
  Modal.confirm({
    title: `确认删除版本 ${v.version}？`,
    content: '删除后该版本的 exe 将不可用于升级，已升级的实例不受影响。',
    onOk: async () => {
      await agentUpgradeApi.deleteVersion(v.version)
      message.success('已删除')
      loadVersions()
    },
  })
}

async function doUpgrade(): Promise<void> {
  if (selectedInstanceIds.value.length === 0 || !selectedVersion.value) {
    message.warning('请选择实例与目标版本')
    return
  }
  upgrading.value = true
  try {
    const result = await agentUpgradeApi.upgrade(selectedInstanceIds.value, selectedVersion.value)
    const ok = result.filter((t) => t.status === 'SUCCESS').length
    message.success(`升级完成：成功 ${ok}/${result.length}`)
    await loadTasks()
    await loadInstances()
  } finally {
    upgrading.value = false
  }
}

const statusLabel: Record<string, string> = { PENDING: '进行中', SUCCESS: '成功', FAILED: '失败' }
const statusColor: Record<string, string> = { PENDING: 'orange', SUCCESS: 'green', FAILED: 'red' }

// platform-audit-logging-ux 11.2：5 段独立进度条
// 段：①下发文件中 ②校验中 ③备份中 ④替换重启中 ⑤等待版本确认中
const STAGES: Array<{ key: string; label: string }> = [
  { key: 'downloading', label: '① 下发文件中' },
  { key: 'verifying', label: '② 校验中' },
  { key: 'backing_up', label: '③ 备份中' },
  { key: 'replacing', label: '④ 替换重启中' },
  { key: 'waiting', label: '⑤ 等待版本确认中' },
]

function stagePercents(task: AgentUpgradeTask): Record<string, number> {
  if (!task.stagePercents) {
    // 无进度信息：成功全 100，失败全 0，进行中按已锁定段推断
    if (task.status === 'SUCCESS') {
      return { downloading: 100, verifying: 100, backing_up: 100, replacing: 100, waiting: 100 }
    }
    return { downloading: 0, verifying: 0, backing_up: 0, replacing: 0, waiting: 0 }
  }
  try {
    return JSON.parse(task.stagePercents)
  } catch {
    return { downloading: 0, verifying: 0, backing_up: 0, replacing: 0, waiting: 0 }
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
    <a-typography-title :level="3">受控端升级</a-typography-title>

    <a-card title="已上传版本" size="small" style="margin-bottom: 16px">
      <a-table :data-source="versions" :loading="loadingVersions" row-key="version" :pagination="false" size="small">
        <a-table-column title="版本" :width="140">
          <template #default="{ record }">{{ record.version }}</template>
        </a-table-column>
        <a-table-column title="MD5" :width="260">
          <template #default="{ record }">
            <a-typography-text code style="font-size: 12px">{{ record.md5 }}</a-typography-text>
          </template>
        </a-table-column>
        <a-table-column title="大小" :width="100">
          <template #default="{ record }">{{ fmtSize(record.size) }}</template>
        </a-table-column>
        <a-table-column title="操作" :width="80">
          <template #default="{ record }">
            <a-button type="link" danger size="small" @click="confirmDeleteVersion(record)">删除</a-button>
          </template>
        </a-table-column>
      </a-table>
    </a-card>

    <a-card title="上传新版受控端" size="small" style="margin-bottom: 16px">
      <a-space>
        <a-input v-model:value="uploadVersion" placeholder="版本号（如 0.2.0）" style="width: 200px" />
        <a-upload :before-upload="handleUpload" :show-upload-list="false" accept=".exe" :disabled="!canUpload">
          <a-button type="primary" :loading="uploading" :disabled="!canUpload">选择 exe 上传</a-button>
        </a-upload>
      </a-space>
      <div v-if="uploading" style="margin-top: 12px">
        <a-typography-text type="secondary" style="font-size: 12px">正在上传… {{ uploadPercent }}%</a-typography-text>
        <a-progress :percent="uploadPercent" :stroke-width="6" style="margin-top: 4px" />
      </div>
    </a-card>

    <a-card title="批量/单独升级" size="small" style="margin-bottom: 16px">
      <a-space style="margin-bottom: 12px">
        <a-select
          v-model:value="selectedVersion"
          placeholder="选择目标版本"
          style="width: 200px"
        >
          <a-select-option v-for="v in versions" :key="v.version" :value="v.version">
            {{ v.version }}
          </a-select-option>
        </a-select>
        <a-button type="primary" :loading="upgrading" @click="doUpgrade">
          升级选中实例（{{ selectedInstanceIds.length }}）
        </a-button>
      </a-space>

      <a-table
        :data-source="instances"
        :loading="instances.length === 0"
        row-key="id"
        :pagination="false"
        :row-selection="{
          selectedRowKeys: selectedInstanceIds,
          onChange: (keys: number[]) => (selectedInstanceIds = keys),
          getCheckboxProps: (record: PhysicalInstance) => ({ disabled: record.status !== 'ONLINE' }),
        }"
        size="small"
      >
        <a-table-column title="编号" data-index="instanceNumber" :width="80" />
        <a-table-column title="机器名" data-index="machineName" />
        <a-table-column title="当前版本" :width="140" :sorter="(a: PhysicalInstance, b: PhysicalInstance) => compareVersions(a.agentVersion, b.agentVersion)">
          <template #default="{ record }">{{ record.agentVersion || '-' }}</template>
        </a-table-column>
        <a-table-column title="状态" :width="90">
          <template #default="{ record }">
            <a-badge :status="record.status === 'ONLINE' ? 'success' : 'error'"
                     :text="record.status === 'ONLINE' ? '在线' : '离线'" />
          </template>
        </a-table-column>
      </a-table>
    </a-card>

    <a-card title="升级任务记录" size="small">
      <a-table :data-source="tasks" :loading="loadingTasks" row-key="id" :pagination="{ pageSize: 10 }" size="small">
        <a-table-column title="实例" :width="80">
          <template #default="{ record }">{{ record.instanceNumber }}</template>
        </a-table-column>
        <a-table-column title="目标版本" :width="120">
          <template #default="{ record }">{{ record.version }}</template>
        </a-table-column>
        <a-table-column title="状态" :width="90">
          <template #default="{ record }">
            <a-tag :color="statusColor[record.status] || 'default'">{{ statusLabel[record.status] || record.status }}</a-tag>
          </template>
        </a-table-column>
        <a-table-column title="升级进度（5 段）" :width="320">
          <template #default="{ record }">
            <div class="stage-list">
              <div v-for="s in STAGES" :key="s.key" class="stage-item">
                <span class="stage-label">{{ s.label }}</span>
                <a-progress
                  :percent="stagePercents(record)[s.key] || 0"
                  :stroke-width="6"
                  :status="stagePercents(record)[s.key] >= 100 ? 'success' : 'active'"
                  size="small"
                />
              </div>
            </div>
          </template>
        </a-table-column>
        <a-table-column title="错误" :width="220">
          <template #default="{ record }">
            <span v-if="record.error" style="color: #f50; font-size: 12px">{{ record.error }}</span>
            <span v-else style="color: #ccc">-</span>
          </template>
        </a-table-column>
        <a-table-column title="开始时间" :width="160">
          <template #default="{ record }">{{ record.createdAt ? dayjs(record.createdAt).format('MM-DD HH:mm:ss') : '-' }}</template>
        </a-table-column>
        <a-table-column title="完成时间" :width="160">
          <template #default="{ record }">{{ record.finishedAt ? dayjs(record.finishedAt).format('MM-DD HH:mm:ss') : '-' }}</template>
        </a-table-column>
      </a-table>
    </a-card>
  </div>
</template>

<style scoped>
.stage-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.stage-item {
  display: flex;
  align-items: center;
  gap: 8px;
}
.stage-label {
  font-size: 12px;
  color: #555;
  white-space: nowrap;
  width: 120px;
}
.stage-item :deep(.ant-progress) {
  flex: 1;
}
</style>
