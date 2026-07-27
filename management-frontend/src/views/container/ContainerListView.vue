<script setup lang="ts">
import { onMounted, onUnmounted, reactive, ref, computed, watch } from 'vue'
import { message, Modal } from 'ant-design-vue'
import dayjs, { type Dayjs } from 'dayjs'
import { containerApi, type Container, type ConnectionInfo } from '@/api/container'
import ContainerTerminal from './ContainerTerminal.vue'
import { instanceApi, type PhysicalInstance } from '@/api/instance'
import { storagePoolApi, type StoragePool } from '@/api/storagePool'
import { imageApi, type ImageMetadata } from '@/api/image'
import { quotaApi, type EffectiveQuota } from '@/api/quota'
import { getSseClient } from '@/utils/sse'

const loading = ref(false)
const containers = ref<Container[]>([])
const instances = ref<PhysicalInstance[]>([])
const pools = ref<StoragePool[]>([])
const images = ref<ImageMetadata[]>([])
const effectiveQuota = ref<EffectiveQuota>({})

const createVisible = ref(false)
const submitting = ref(false)
// platform-refinements #1：创建过程阶段提示
const createProgressVisible = ref(false)
const createStage = ref('容器下发中...')
let createStageTimer: ReturnType<typeof setInterval> | null = null
const form = reactive({
  instanceId: undefined as number | undefined,
  imageRef: '',
  storagePoolId: undefined as number | undefined,
  projectName: '',
  sshPassword: '',
  remark: '',
  cpuLimit: undefined as number | undefined,
  memoryLimit: undefined as number | undefined,
  gpuMemoryLimit: undefined as number | undefined,
  shmSize: undefined as number | undefined,
  containerPorts: [] as number[],
  env: [] as string[],
})

const connVisible = ref(false)
const connInfo = ref<ConnectionInfo | null>(null)

const sshVisible = ref(false)
const sshContainerId = ref(0)
const sshPassword = ref('')

// 表单配置快照查看（任务 4）
const snapshotVisible = ref(false)
const snapshotContent = ref('')

// 容器提交镜像持久化（platform-refinements 6.7）
const commitVisible = ref(false)
const commitContainerId = ref(0)
const commitForm = reactive({
  imageName: '',
  imageTag: 'latest',
  project: '',
  note: '',
})
// platform-refinements #3a：提交镜像进度
const commitProgressVisible = ref(false)
const commitStage = ref('提交中…')
let commitStageTimer: ReturnType<typeof setInterval> | null = null

// 容器共享（platform-refinements #2）
const shareVisible = ref(false)
const shareContainer = ref<Container | null>(null)
const shareForm = reactive({ targetWorkerId: '', expiresAt: null as Dayjs | null })

// 查看日志（platform-refinements #2）
const logsVisible = ref(false)
const logsContainerId = ref(0)
const logsTail = ref(200)
const logsLive = ref(false)
const logsContent = ref('')
const logsLoading = ref(false)
let logsTimer: ReturnType<typeof setInterval> | null = null

// 备注编辑（platform-refinements #1）
const remarkVisible = ref(false)
const remarkId = ref(0)
const remarkValue = ref('')

// 在线终端（platform-refinements #2）
const terminalVisible = ref(false)
const terminalContainerId = ref(0)

// 提交镜像：project 默认取容器所挂存储池的项目名（命名格式 物理机编号-工号-项目名 中的用户自定义段），用户可改
function openCommit(c: Container): void {
  commitContainerId.value = c.id
  // 镜像名建议取原镜像 ref 的 name 段
  commitForm.imageName = c.imageRef.split(':')[0] || ''
  commitForm.imageTag = 'latest'
  commitForm.project = c.projectName || ''
  commitForm.note = ''
  commitVisible.value = true
}

async function handleCommit(): Promise<void> {
  if (!commitForm.imageName) {
    message.warning('请填写镜像名')
    return
  }
  submitting.value = true
  // platform-refinements #3a：提交镜像阶段进度
  commitStage.value = '提交中…'
  commitProgressVisible.value = true
  let tick = 0
  commitStageTimer = setInterval(() => {
    tick += 1
    commitStage.value = ['提交中…', '打包中…', '上传中…'][tick % 3]
  }, 5000)
  try {
    await containerApi.commitImage(commitContainerId.value, {
      imageName: commitForm.imageName,
      imageTag: commitForm.imageTag,
      project: commitForm.project || undefined,
      note: commitForm.note || undefined,
    })
    message.success('镜像提交持久化已完成，回传成功后可在镜像管理查看')
    commitVisible.value = false
  } catch {
    // 拦截器已提示
  } finally {
    submitting.value = false
    if (commitStageTimer) clearInterval(commitStageTimer)
    commitProgressVisible.value = false
  }
}

// 共享容器（platform-refinements #2）
function openShare(c: Container): void {
  shareContainer.value = c
  shareForm.targetWorkerId = ''
  shareForm.expiresAt = null
  shareVisible.value = true
}

async function handleShare(): Promise<void> {
  if (!shareContainer.value) return
  if (!shareForm.targetWorkerId.trim()) {
    message.warning('请输入工号')
    return
  }
  try {
    await containerApi.share(shareContainer.value.id, {
      targetWorkerId: shareForm.targetWorkerId.trim(),
      expiresAt: shareForm.expiresAt ? shareForm.expiresAt.toISOString() : undefined,
    })
    message.success('已共享')
    shareVisible.value = false
    load()
  } catch {
    // 拦截器已提示
  }
}

async function revokeShare(containerId: number, shareId: number): Promise<void> {
  try {
    await containerApi.unshare(containerId, shareId)
    message.success('已取消共享')
    load()
  } catch {
    // 拦截器已提示
  }
}

// 查看日志（platform-refinements #2）
async function openLogs(c: Container): Promise<void> {
  logsContainerId.value = c.id
  logsLive.value = false
  logsContent.value = ''
  logsVisible.value = true
  await fetchLogs()
}

async function fetchLogs(): Promise<void> {
  logsLoading.value = true
  try {
    logsContent.value = await containerApi.logs(logsContainerId.value, logsTail.value)
  } catch {
    // 拦截器已提示
  } finally {
    logsLoading.value = false
  }
}

function toggleLive(checked: boolean): void {
  if (checked) {
    logsTimer = setInterval(fetchLogs, 2000)
  } else if (logsTimer) {
    clearInterval(logsTimer)
    logsTimer = null
  }
}

function closeLogs(): void {
  if (logsTimer) { clearInterval(logsTimer); logsTimer = null }
  logsVisible.value = false
}

// 备注编辑（platform-refinements #1）
function openRemark(c: Container): void {
  remarkId.value = c.id
  remarkValue.value = c.remark ?? ''
  remarkVisible.value = true
}

async function handleRemark(): Promise<void> {
  try {
    await containerApi.updateRemark(remarkId.value, remarkValue.value)
    message.success('备注已更新')
    remarkVisible.value = false
    load()
  } catch {
    // 拦截器已提示
  }
}

// 在线终端（platform-refinements #2）
function openTerminal(c: Container): void {
  terminalContainerId.value = c.id
  terminalVisible.value = true
}

// 环境变量管理
function addEnv(): void {
  form.env.push('')
}
function removeEnv(index: number): void {
  form.env.splice(index, 1)
}

const instanceFilter = (input: string, option: { label?: string }) => {
  const label = option?.label ?? ''
  return label.toLowerCase().includes(input.toLowerCase())
}

onMounted(load)

// platform-improvements 任务 4.4：订阅 container SSE 事件，实时更新容器列表状态
let offContainerSse: (() => void) | null = null
onMounted(() => {
  offContainerSse = getSseClient().on('container', (data) => {
    const ev = data as { containerId?: number; status?: string }
    if (!ev || !ev.containerId) return
    const target = containers.value.find((c) => c.id === ev.containerId)
    if (target && ev.status && target.status !== ev.status) {
      target.status = ev.status
    }
  })
})
onUnmounted(() => {
  offContainerSse?.()
  if (logsTimer) clearInterval(logsTimer)
})

async function load(): Promise<void> {
  loading.value = true
  try {
    containers.value = await containerApi.list()
    instances.value = await instanceApi.list()
    pools.value = await storagePoolApi.list()
    images.value = await imageApi.list()
  } catch {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

async function handleCreate(): Promise<void> {
  if (!form.instanceId || !form.imageRef || !form.sshPassword || !form.projectName || !form.storagePoolId) {
    message.warning('请填写必填项（物理实例、镜像、存储池、项目名、SSH 密码）')
    return
  }

  // 构建表单快照（JSON，任务 4：容器配置页可回显原始表单）
  // 表单中内存/SHM 以 MB 录入（与配额单位一致），提交时换算为字节（受控端按字节设置）
  const MB = 1024 * 1024
  const formSnapshot = JSON.stringify({
    instanceId: form.instanceId,
    imageRef: form.imageRef,
    storagePoolId: form.storagePoolId,
    projectName: form.projectName,
    cpuLimit: form.cpuLimit,
    memoryLimitMb: form.memoryLimit,
    gpuMemoryLimit: form.gpuMemoryLimit,
    shmSizeMb: form.shmSize,
    containerPorts: form.containerPorts,
    env: form.env,
    sshPasswordSet: !!form.sshPassword,
  }, null, 2)

  submitting.value = true
  // platform-refinements #1：显示阶段进度提示
  createStage.value = '容器下发中...'
  createProgressVisible.value = true
  let stageTick = 0
  createStageTimer = setInterval(() => {
    stageTick += 1
    createStage.value = stageTick % 2 === 0 ? '容器下发中...' : '容器创建中...'
  }, 4000)
  try {
    await containerApi.create({
      instanceId: form.instanceId,
      imageRef: form.imageRef,
      storagePoolId: form.storagePoolId,
      projectName: form.projectName,
      sshPassword: form.sshPassword,
      cpuLimit: form.cpuLimit,
      memoryLimit: form.memoryLimit != null ? form.memoryLimit * MB : undefined,
      gpuMemoryLimit: form.gpuMemoryLimit,
      shmSize: form.shmSize != null ? form.shmSize * MB : undefined,
      containerPorts: form.containerPorts,
      env: form.env,
      formSnapshot,
      remark: form.remark,
    })
    message.success('容器创建成功')
    createVisible.value = false
    resetForm()
    load()
  } catch {
    // 拦截器已提示
  } finally {
    submitting.value = false
    if (createStageTimer) clearInterval(createStageTimer)
    createProgressVisible.value = false
  }
}

function resetForm(): void {
  form.imageRef = ''
  form.storagePoolId = undefined
  form.projectName = ''
  form.sshPassword = ''
  form.remark = ''
  form.cpuLimit = undefined
  form.memoryLimit = undefined
  form.gpuMemoryLimit = undefined
  form.shmSize = undefined
  form.containerPorts = []
  form.env = []
}

async function showConnection(c: Container): Promise<void> {
  connInfo.value = await containerApi.connectionInfo(c.id)
  connVisible.value = true
}

function showResetSsh(c: Container): void {
  sshContainerId.value = c.id
  sshPassword.value = ''
  sshVisible.value = true
}

async function handleResetSsh(): Promise<void> {
  if (!sshPassword.value) {
    message.warning('请输入新密码')
    return
  }
  await containerApi.resetSsh(sshContainerId.value, sshPassword.value)
  message.success('SSH 密码已重置')
  sshVisible.value = false
}

async function showSnapshot(c: Container): Promise<void> {
  try {
    snapshotContent.value = await containerApi.formSnapshot(c.id)
    snapshotVisible.value = true
  } catch {
    // 拦截器已提示
  }
}

function lifecycle(c: Container, action: string, confirmText?: string): void {
  const doAction = async () => {
    try {
      await containerApi.lifecycle(c.id, action)
      message.success(`${action} 成功`)
      load()
    } catch {
      // 拦截器已提示
    }
  }
  if (confirmText) {
    Modal.confirm({ title: confirmText, onOk: doAction })
  } else {
    doAction()
  }
}

const statusColor: Record<string, string> = {
  RUNNING: 'green', STOPPED: 'orange', CREATED: 'blue', EXITED: 'gray', REMOVED: 'red',
}

// 存储池状态映射（与 ACTIVE/MIGRATING/MIGRATED 共存，离线为派生标识）
const poolStatusLabel: Record<string, string> = {
  ACTIVE: '正常', MIGRATING: '迁移中', MIGRATED: '已迁移',
}
const poolStatusColor: Record<string, string> = {
  ACTIVE: 'green', MIGRATING: 'orange', MIGRATED: 'blue',
}

// 当前物理实例上可挂载的存储池（自有 + 被共享授权），按所选实例过滤
const availablePools = computed<StoragePool[]>(() => {
  if (!form.instanceId) return []
  return pools.value.filter((p) => p.instanceId === form.instanceId)
})

// 选定镜像（用于端口预填，任务 2.7）
const selectedImage = computed<ImageMetadata | null>(() =>
  images.value.find((i) => `${i.name}:${i.tag}` === form.imageRef) || null,
)

// 实例 IP 查找（任务 6.1：容器列表 IP 列，join 物理机 IP）
const instanceIpMap = computed<Record<number, string>>(() => {
  const m: Record<number, string> = {}
  for (const i of instances.value) m[i.id] = i.ipAddress || '-'
  return m
})

// 选择物理实例后：加载有效配额并填默认值（任务 2.5），重置存储池选择
watch(() => form.instanceId, async (id) => {
  form.storagePoolId = undefined
  if (!id) {
    effectiveQuota.value = {}
    return
  }
  try {
    effectiveQuota.value = await quotaApi.effective(id)
    // 资源限制默认填有效配额（每维若设了上限则取上限，否则留空=不限）
    form.cpuLimit = effectiveQuota.value.maxCpuCores
    form.memoryLimit = effectiveQuota.value.maxMemoryMb
    form.gpuMemoryLimit = effectiveQuota.value.maxGpuMemoryMb
    form.shmSize = effectiveQuota.value.maxShmMb
  } catch {
    effectiveQuota.value = {}
  }
})

// 选择镜像后：将镜像应用端口预填到"容器内端口"（SSH:22 始终含，可增删，任务 2.7）
watch(selectedImage, (img) => {
  if (!img) return
  const ports = new Set<number>([22])
  if (img.appPorts && img.appPorts.length) {
    img.appPorts.forEach((p) => ports.add(p))
  }
  form.containerPorts = Array.from(ports)
})
</script>

<template>
  <div>
    <div style="display: flex; justify-content: space-between; margin-bottom: 16px">
      <a-typography-title :level="3" style="margin: 0">容器配置</a-typography-title>
      <a-button type="primary" @click="createVisible = true">创建容器</a-button>
    </div>

    <a-table :data-source="containers" :loading="loading" row-key="id" :pagination="false" :scroll="{ x: 'max-content' }" class="auto-table">
      <a-table-column title="容器名" data-index="name" :sorter="(a: Container, b: Container) => a.name.localeCompare(b.name)" />
      <a-table-column title="所有人" :width="100" :sorter="(a: Container, b: Container) => (a.ownerName||'').localeCompare(b.ownerName||'')">
        <template #default="{ record }">{{ record.ownerName || '-' }}</template>
      </a-table-column>
      <a-table-column title="项目名" data-index="projectName" :width="120" :sorter="(a: Container, b: Container) => (a.projectName||'').localeCompare(b.projectName||'')" />
      <a-table-column title="镜像" data-index="imageRef" />
      <a-table-column title="备注" :width="160">
        <template #default="{ record }">
          <a-tooltip v-if="record.remark" :title="record.remark">
            <span>{{ record.remark.length > 20 ? record.remark.slice(0, 20) + '…' : record.remark }}</span>
          </a-tooltip>
          <span v-else style="color: #ccc">-</span>
        </template>
      </a-table-column>
      <a-table-column title="共有人" :width="160">
        <template #default="{ record }">
          <template v-if="record.sharedWith && record.sharedWith.length">
            <a-tag
              v-for="s in record.sharedWith" :key="s.id"
              :color="s.expired ? 'default' : 'blue'"
              style="margin: 2px"
            >
              {{ s.realName }}{{ s.expired ? '(过期)' : '' }}
            </a-tag>
          </template>
          <span v-else style="color: #ccc">-</span>
        </template>
      </a-table-column>
      <a-table-column title="物理机" data-index="instanceNumber" :width="80" :sorter="(a: Container, b: Container) => (a.instanceNumber||'').localeCompare(b.instanceNumber||'')" />
      <a-table-column title="IP地址" :width="130">
        <template #default="{ record }">{{ instanceIpMap[record.instanceId] || '-' }}</template>
      </a-table-column>
      <a-table-column title="状态" :width="110" :sorter="(a: Container, b: Container) => a.status.localeCompare(b.status)">
        <template #default="{ record }">
          <a-tag v-if="record.instanceOnline === false" color="red">物理实例不在线</a-tag>
          <a-tag v-else :color="statusColor[record.status]">{{ record.status }}</a-tag>
        </template>
      </a-table-column>
      <a-table-column title="创建时间" :width="160" :sorter="(a: Container, b: Container) => a.createdAt.localeCompare(b.createdAt)">
        <template #default="{ record }">{{ dayjs(record.createdAt).format('YYYY-MM-DD HH:mm') }}</template>
      </a-table-column>
      <a-table-column title="操作" :width="460">
        <template #default="{ record }">
          <a-button type="link" size="small" @click="showConnection(record)">连接信息</a-button>
          <a-tooltip :title="record.instanceOnline === false ? '物理实例不在线，操作不可用' : ''">
            <span>
              <a-button type="link" size="small" :disabled="record.instanceOnline === false" @click="openCommit(record)">提交镜像</a-button>
              <a-button type="link" size="small" :disabled="record.instanceOnline === false" @click="openShare(record)">共享</a-button>
              <a-button type="link" size="small" :disabled="record.instanceOnline === false" @click="openLogs(record)">日志</a-button>
              <a-button type="link" size="small" :disabled="record.instanceOnline === false" @click="openTerminal(record)">终端</a-button>
              <a-button type="link" size="small" :disabled="record.instanceOnline === false" @click="openRemark(record)">备注</a-button>
              <a-button v-if="record.status !== 'RUNNING'" type="link" size="small" :disabled="record.instanceOnline === false" @click="lifecycle(record, 'start')">启动</a-button>
              <a-button v-if="record.status === 'RUNNING'" type="link" size="small" :disabled="record.instanceOnline === false" @click="lifecycle(record, 'stop')">停止</a-button>
              <a-button type="link" size="small" :disabled="record.instanceOnline === false" @click="lifecycle(record, 'restart')">重启</a-button>
              <a-button type="link" size="small" :disabled="record.instanceOnline === false" @click="showResetSsh(record)">重置SSH</a-button>
              <a-button
                v-if="record.status !== 'RUNNING'"
                type="link" danger size="small"
                :disabled="record.instanceOnline === false"
                @click="lifecycle(record, 'rm', '确认删除容器？存储池数据保留')"
              >删除</a-button>
              <a-tooltip v-else title="请先停止容器再删除">
                <a-button type="link" danger size="small" disabled>删除</a-button>
              </a-tooltip>
            </span>
          </a-tooltip>
          <a-button type="link" size="small" @click="showSnapshot(record)">查看配置</a-button>
        </template>
      </a-table-column>
    </a-table>

    <!-- 创建容器表单 -->
    <a-modal v-model:open="createVisible" title="创建容器" width="680px" :confirm-loading="submitting" @ok="handleCreate">
      <a-form layout="vertical">
        <a-form-item label="物理实例" required>
          <a-select v-model:value="form.instanceId" placeholder="选择物理实例" :filter-option="instanceFilter" show-search>
            <a-select-option v-for="i in instances" :key="i.id" :value="i.id" :label="i.instanceNumber + ' - ' + (i.machineName||'')">
              <span>{{ i.instanceNumber }} - {{ i.machineName || '未命名' }}</span>
              <a-tag :color="i.status === 'ONLINE' ? 'green' : 'default'" style="margin-left: 8px">
                {{ i.status === 'ONLINE' ? '在线' : '离线' }}
              </a-tag>
            </a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item label="项目名" required>
          <a-input v-model:value="form.projectName" placeholder="如 bert-finetune（用于容器命名：学号-项目名-随机串）" />
        </a-form-item>
        <a-form-item label="镜像" required>
          <a-select
            v-model:value="form.imageRef"
            placeholder="选择镜像（仅限已配置镜像）"
            show-search
            :filter-option="instanceFilter"
          >
            <a-select-option
              v-for="i in images"
              :key="i.id"
              :value="`${i.name}:${i.tag}`"
              :label="i.name + ':' + i.tag"
            >
              {{ i.name }}:{{ i.tag }}
              <a-tag v-if="i.isPublic" color="purple" style="margin-left: 8px">公共</a-tag>
              <span v-else-if="i.ownerName" style="margin-left: 8px; color: #999">{{ i.ownerName }}</span>
            </a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item label="存储池" required>
          <a-select
            v-model:value="form.storagePoolId"
            placeholder="选择存储池（必选）"
            :disabled="!form.instanceId"
          >
            <a-select-option
              v-for="p in availablePools"
              :key="p.id"
              :value="p.id"
              :disabled="!!p.offline"
            >
              {{ p.poolName }}
              <a-tag :color="poolStatusColor[p.status]" style="margin-left: 8px">
                {{ poolStatusLabel[p.status] }}
              </a-tag>
              <a-tag v-if="p.offline" color="red" style="margin-left: 4px">离线</a-tag>
            </a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item label="SSH 密码" required>
          <a-input-password v-model:value="form.sshPassword" placeholder="设置容器 SSH 登录密码" />
          <div style="font-size: 12px; color: #999; margin-top: 4px">
            用于容器 SSH 登录，明文存储于容器并下发受控端，运行中可重置。若容器未安装 SSH 服务，则无法连接。
          </div>
        </a-form-item>
        <a-form-item label="备注">
          <a-input v-model:value="form.remark" placeholder="容器备注（可选）" />
        </a-form-item>
        <a-row :gutter="16">
          <a-col :span="6">
            <a-form-item label="CPU（核）">
              <a-input-number
                v-model:value="form.cpuLimit"
                :min="0.5" :max="effectiveQuota.maxCpuCores" :step="0.5"
                style="width: 100%"
              />
              <div v-if="effectiveQuota.maxCpuCores" style="font-size: 12px; color: #999">
                [0.5, {{ effectiveQuota.maxCpuCores }}]
              </div>
            </a-form-item>
          </a-col>
          <a-col :span="6">
            <a-form-item label="内存（MB）">
              <a-input-number
                v-model:value="form.memoryLimit"
                :min="512" :max="effectiveQuota.maxMemoryMb" style="width: 100%"
              />
              <div v-if="effectiveQuota.maxMemoryMb" style="font-size: 12px; color: #999">
                [512, {{ effectiveQuota.maxMemoryMb }}]
              </div>
            </a-form-item>
          </a-col>
          <a-col :span="6">
            <a-form-item label="GPU显存（MB）">
              <a-input-number
                v-model:value="form.gpuMemoryLimit"
                :min="0" :max="effectiveQuota.maxGpuMemoryMb" style="width: 100%"
              />
              <div v-if="effectiveQuota.maxGpuMemoryMb" style="font-size: 12px; color: #999">
                [0, {{ effectiveQuota.maxGpuMemoryMb }}]
              </div>
            </a-form-item>
          </a-col>
          <a-col :span="6">
            <a-form-item label="SHM（MB）">
              <a-input-number
                v-model:value="form.shmSize"
                :min="64" :max="effectiveQuota.maxShmMb" style="width: 100%"
              />
              <div v-if="effectiveQuota.maxShmMb" style="font-size: 12px; color: #999">
                [64, {{ effectiveQuota.maxShmMb }}]
              </div>
            </a-form-item>
          </a-col>
        </a-row>
        <a-form-item label="容器内端口（SSH:22 始终包含；选择镜像后其应用端口自动预填，可增删）">
          <a-select v-model:value="form.containerPorts" mode="tags" placeholder="如 8888, 6006" />
        </a-form-item>
        <a-form-item label="环境变量">
          <div v-for="(_, i) in form.env" :key="i" style="display: flex; gap: 8px; margin-bottom: 8px">
            <a-input v-model:value="form.env[i]" placeholder="KEY=VALUE，如 CUDA_VISIBLE_DEVICES=0" />
            <a-button danger size="small" @click="removeEnv(i)">删除</a-button>
          </div>
          <a-button type="dashed" size="small" @click="addEnv">+ 添加环境变量</a-button>
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- 创建过程阶段提示（platform-refinements #1） -->
    <a-modal
      v-model:open="createProgressVisible"
      :closable="false"
      :maskClosable="false"
      :footer="null"
      :keyboard="false"
      width="420px"
      title="容器创建"
    >
      <div style="text-align: center; padding: 16px 0">
        <a-progress :percent="75" status="active" :show-info="false" />
        <p style="margin-top: 16px; font-size: 15px">{{ createStage }}</p>
        <p style="color: #999; font-size: 12px">正在与受控端通信，请稍候…</p>
      </div>
    </a-modal>

    <!-- 连接信息 -->
    <a-modal v-model:open="connVisible" title="连接信息" :footer="null">
      <div v-if="connInfo">
        <a-descriptions v-if="connInfo.ssh" :column="1" bordered>
          <a-descriptions-item label="SSH 连接">
            {{ connInfo.ssh.host }}:{{ connInfo.ssh.port }}
          </a-descriptions-item>
        </a-descriptions>
        <a-alert
          style="margin-top: 12px"
          type="info"
          show-icon
          message="SSH 密码"
          description="SSH 密码在创建容器时设置，明文存储于容器；运行中可经此重置（即时生效）。若容器未安装 SSH 服务，则无法连接。"
        />
        <a-typography-title v-if="connInfo.apps?.length" :level="5" style="margin-top: 16px">应用端口</a-typography-title>
        <a-table
          v-if="connInfo.apps?.length"
          :data-source="connInfo.apps"
          :pagination="false"
          size="small"
          row-key="hostPort"
        >
          <a-table-column title="暴露端口" data-index="hostPort" :width="120" />
          <a-table-column title="容器内端口" data-index="containerPort" :width="120" />
          <a-table-column title="访问地址">
            <template #default="{ record }">{{ connInfo.ssh?.host }}:{{ record.hostPort }}</template>
          </a-table-column>
        </a-table>
      </div>
    </a-modal>

    <!-- SSH 密码重置 -->
    <a-modal v-model:open="sshVisible" title="重置 SSH 密码" @ok="handleResetSsh">
      <a-input-password v-model:value="sshPassword" placeholder="输入新 SSH 密码" />
      <div style="font-size: 12px; color: #999; margin-top: 4px">
        重置即时生效（运行中容器 docker exec 更新），明文存储于容器。
      </div>
    </a-modal>

    <!-- 容器提交镜像持久化（platform-refinements 6.7） -->
    <a-modal
      v-model:open="commitVisible"
      title="提交镜像持久化"
      :confirm-loading="submitting"
      @ok="handleCommit"
    >
      <a-form layout="vertical">
        <a-form-item label="镜像名" required>
          <a-input v-model:value="commitForm.imageName" placeholder="如 my-pytorch-snapshot" />
        </a-form-item>
        <a-form-item label="标签">
          <a-input v-model:value="commitForm.imageTag" placeholder="latest" />
        </a-form-item>
        <a-form-item label="项目">
          <a-input v-model:value="commitForm.project" placeholder="所属项目（默认取容器存储池项目名，可改）" />
        </a-form-item>
        <a-form-item label="备注">
          <a-textarea v-model:value="commitForm.note" :rows="2" placeholder="镜像备注（可选）" />
        </a-form-item>
        <a-alert
          type="info"
          show-icon
          message="受控端将 docker commit + save 导出 tar 并回传管理端，回传完成方可使用该镜像。"
        />
      </a-form>
    </a-modal>

    <!-- 提交镜像进度（platform-refinements #3a） -->
    <a-modal
      v-model:open="commitProgressVisible"
      :closable="false"
      :maskClosable="false"
      :footer="null"
      :keyboard="false"
      width="420px"
      title="提交镜像"
    >
      <div style="text-align: center; padding: 16px 0">
        <a-progress :percent="75" status="active" :show-info="false" />
        <p style="margin-top: 16px; font-size: 15px">{{ commitStage }}</p>
        <p style="color: #999; font-size: 12px">受控端正在 commit + save + 回传，请稍候…</p>
      </div>
    </a-modal>

    <!-- 备注编辑（platform-refinements #1） -->
    <a-modal v-model:open="remarkVisible" title="容器备注" @ok="handleRemark">
      <a-textarea v-model:value="remarkValue" :rows="3" placeholder="输入备注（可选）" />
    </a-modal>

    <!-- 在线终端（platform-refinements #2） -->
    <ContainerTerminal v-model:visible="terminalVisible" :container-id="terminalContainerId" />

    <!-- 查看日志（platform-refinements #2） -->
    <a-modal v-model:open="logsVisible" title="容器日志" width="800px" :footer="null" @cancel="closeLogs">
      <a-space style="margin-bottom: 12px">
        <span>近</span>
        <a-input-number v-model:value="logsTail" :min="10" :max="5000" :step="50" style="width: 100px" />
        <span>条</span>
        <a-button size="small" :loading="logsLoading" @click="fetchLogs">刷新</a-button>
        <a-checkbox v-model:checked="logsLive" @change="(e: any) => toggleLive(e.target.checked)">实时</a-checkbox>
      </a-space>
      <pre style="background: #1e1e1e; color: #d4d4d4; padding: 12px; border-radius: 4px; max-height: 460px; overflow: auto; white-space: pre-wrap; font-size: 12px">{{ logsContent || '(无日志)' }}</pre>
    </a-modal>

    <!-- 容器共享（platform-refinements #2） -->
    <a-modal v-model:open="shareVisible" title="共享容器" @ok="handleShare">      <a-form layout="vertical">
        <a-form-item label="目标用户工号" required>
          <a-input v-model:value="shareForm.targetWorkerId" placeholder="输入工号精准匹配用户" />
        </a-form-item>
        <a-form-item label="到期时间（留空=永久）">
          <a-date-picker
            v-model:value="shareForm.expiresAt"
            show-time
            format="YYYY-MM-DD HH:mm"
            style="width: 100%"
            placeholder="选择到期时间"
          />
        </a-form-item>
        <div v-if="shareContainer && shareContainer.sharedWith && shareContainer.sharedWith.length" style="margin-top: 8px">
          <a-typography-text type="secondary">当前共有人：</a-typography-text>
          <div style="margin-top: 8px">
            <a-tag
              v-for="s in shareContainer.sharedWith" :key="s.id"
              :color="s.expired ? 'default' : 'blue'" closable
              @close="revokeShare(shareContainer!.id, s.id)"
            >
              {{ s.realName }}{{ s.workerId ? '（' + s.workerId + '）' : '' }}{{ s.expired ? '(过期)' : '' }}
            </a-tag>
          </div>
        </div>
      </a-form>
    </a-modal>

    <!-- 表单配置快照（任务 4：回显原始配置） -->
    <a-modal v-model:open="snapshotVisible" title="容器创建配置" width="600px" :footer="null">
      <pre style="background: #f5f5f5; padding: 16px; border-radius: 8px; font-size: 12px; max-height: 500px; overflow: auto">{{ snapshotContent || '无配置快照' }}</pre>
    </a-modal>
  </div>
</template>

<style scoped>
/* platform-audit-logging-ux：表头宽度自适应、不换行 */
.auto-table :deep(.ant-table-thead > tr > th) {
  white-space: nowrap;
}
.auto-table :deep(.ant-table-tbody > tr > td) {
  white-space: nowrap;
}
</style>
