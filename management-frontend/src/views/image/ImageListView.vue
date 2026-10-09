<script setup lang="ts">
import { onMounted, onUnmounted, reactive, ref, computed } from 'vue'
import { Modal, message } from 'ant-design-vue'
import dayjs from 'dayjs'
import {
  imageApi,
  type ImageMetadata,
  type PushCommands,
  type UntaggedImage,
  type ImageSyncTask,
  type SyncBatchView,
  type ImageSyncProgressEvent,
} from '@/api/image'
import { groupApi, type ResearchGroup } from '@/api/group'
import { copyToClipboard } from '@/utils/clipboard'
import { getSseClient } from '@/utils/sse'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
// platform-refinements：管理员视角为"权限"管理，普通用户为"共享"自己的镜像
const shareLabel = computed(() => (auth.role === 'ADMIN' ? '权限' : '共享'))
// registry-image-distribution：无标记镜像入口仅管理员
const isAdmin = computed(() => auth.role === 'ADMIN')
// 复核反馈：管理员可操作全部镜像；其他角色仅可操作（编辑/上传/刷新/共享/删除）自己的
function canOperate(image: ImageMetadata): boolean {
  return isAdmin.value || image.ownerId === auth.user?.id
}

const loading = ref(false)
const images = ref<ImageMetadata[]>([])
// 主 tab：list=镜像列表 / untagged=无标记镜像（仅管理员）
const activeTab = ref('list')

// 登记镜像弹窗（registry-image-distribution D3：纯元数据，无文件上传）
const registerVisible = ref(false)
const submitting = ref(false)
const registerForm = reactive({
  name: '',
  tag: 'latest',
  appPorts: [] as number[],
  usageInstructions: '',
  mountPoint: '',
})

// 上传命令弹窗（D3：tag/push 命令由后端拼装，一键复制）
const pushCmdVisible = ref(false)
const pushCmds = ref<PushCommands | null>(null)

// 共享弹窗（platform-refinements 6.8：按工号 / 共享给课题组）
const shareVisible = ref(false)
const shareImage = ref<ImageMetadata | null>(null)
// platform-refinements：共享/权限模式--worker=按工号、group=共享给课题组、all=全员可见、private=仅本人
const shareForm = reactive({
  mode: 'worker' as 'worker' | 'group' | 'all' | 'private',
  targetWorkerIds: [] as string[],
  targetGroupId: undefined as number | undefined,
})
const groups = ref<ResearchGroup[]>([])

// 编辑元数据弹窗（应用端口 + 使用说明）
const editVisible = ref(false)
const editSubmitting = ref(false)
const editForm = reactive({
  id: 0,
  name: '',
  tag: '',
  appPorts: [] as number[],
  usageInstructions: '',
  mountPoint: '',
})

// 无标记镜像（registry-image-distribution D3，仅管理员）
const untaggedLoading = ref(false)
const untagged = ref<UntaggedImage[]>([])
const claimVisible = ref(false)
const claimSubmitting = ref(false)
const claimForm = reactive({
  repo: '',
  tag: 'latest',
  appPorts: [] as number[],
  usageInstructions: '',
  mountPoint: '',
  visibility: 'SHARED_TO_ALL' as 'SHARED_TO_ALL' | 'PRIVATE',
})

// ===== 同步到所有机器（V36，仅管理员）=====
const syncDrawerVisible = ref(false)
const syncView = ref<SyncBatchView | null>(null)
let syncUnsubscribe: (() => void) | null = null

function syncStatusLabel(s: string): { text: string; color: string } {
  switch (s) {
    case 'PENDING': return { text: '等待中', color: 'default' }
    case 'PULLING': return { text: '拉取中', color: 'processing' }
    case 'PULLED': return { text: '完成', color: 'green' }
    case 'ALREADY_EXISTS': return { text: '已存在', color: 'cyan' }
    case 'FAILED': return { text: '失败', color: 'red' }
    case 'TIMEOUT': return { text: '超时', color: 'orange' }
    case 'OFFLINE_SKIPPED': return { text: '离线跳过', color: 'default' }
    default: return { text: s, color: 'default' }
  }
}

// SSE 进度事件按实例更新对应行，并本地重算计数（无需再拉列表）
function applySyncEvent(ev: ImageSyncProgressEvent): void {
  const v = syncView.value
  if (!v || ev.batchId !== v.batch.id) return
  const target = v.tasks.find((t) => t.instanceNumber === ev.instanceNumber)
  if (!target) return
  target.status = ev.status as ImageSyncTask['status']
  target.percent = ev.percent
  if (ev.text !== undefined) target.lastText = ev.text
  if (ev.error !== undefined) target.errorMessage = ev.error
  v.pulled = v.tasks.filter((t) => t.status === 'PULLED').length
  v.alreadyExists = v.tasks.filter((t) => t.status === 'ALREADY_EXISTS').length
  v.failed = v.tasks.filter((t) => t.status === 'FAILED' || t.status === 'TIMEOUT').length
  v.offlineSkipped = v.tasks.filter((t) => t.status === 'OFFLINE_SKIPPED').length
  if (v.tasks.every((t) => t.status !== 'PENDING' && t.status !== 'PULLING')) {
    v.batch.status = 'DONE'
  }
}

function ensureSyncSubscription(): void {
  if (syncUnsubscribe) return
  syncUnsubscribe = getSseClient().on('imageSyncProgress', (data) => applySyncEvent(data as ImageSyncProgressEvent))
}

onUnmounted(() => {
  syncUnsubscribe?.()
  syncUnsubscribe = null
})

// 点击按钮：优先恢复该镜像进行中的批次（刷新场景），否则确认后发起新批次
async function openSyncDrawer(image: ImageMetadata): Promise<void> {
  ensureSyncSubscription()
  try {
    const active = await imageApi.getActiveSyncBatch(image.id)
    if (active) {
      syncView.value = active
      syncDrawerVisible.value = true
      return
    }
  } catch {
    // 拦截器已提示
  }
  Modal.confirm({
    title: `同步 ${image.name}:${image.tag} 到所有机器？`,
    content: '将为全部当前在线的物理机下发镜像拉取指令（离线机器标记跳过），各机器拉取进度实时展示。',
    okText: '开始同步',
    onOk: async () => {
      try {
        syncView.value = await imageApi.syncAll(image.id)
        syncDrawerVisible.value = true
      } catch {
        // 拦截器已提示（如已有批次进行中）
      }
    },
  })
}

function openEdit(image: ImageMetadata): void {
  editForm.id = image.id
  editForm.name = image.name
  editForm.tag = image.tag
  editForm.appPorts = [...(image.appPorts ?? [])]
  editForm.usageInstructions = image.usageInstructions ?? ''
  editForm.mountPoint = image.mountPoint ?? ''
  editVisible.value = true
}

function editPortChange(ports: (string | number)[]): void {
  editForm.appPorts = dedupePorts(ports)
}

async function handleEditSubmit(): Promise<void> {
  editSubmitting.value = true
  try {
    await imageApi.editMetadata(
      editForm.id,
      editForm.appPorts,
      editForm.usageInstructions,
      editForm.mountPoint || undefined,
    )
    message.success('已更新应用端口、使用说明与容器内挂载点')
    editVisible.value = false
    load()
  } catch {
    // 拦截器已提示
  } finally {
    editSubmitting.value = false
  }
}

onMounted(load)

async function load(): Promise<void> {
  loading.value = true
  try {
    images.value = await imageApi.list()
  } finally {
    loading.value = false
  }
}

function showRegister(): void {
  registerForm.name = ''
  registerForm.tag = 'latest'
  registerForm.appPorts = []
  registerForm.usageInstructions = ''
  registerForm.mountPoint = ''
  registerVisible.value = true
}

function registerPortChange(ports: (string | number)[]): void {
  registerForm.appPorts = dedupePorts(ports)
}

// 应用端口去重 + 校验（单个镜像同一端口只保留一个；端口须为 1-65535 整数）
function dedupePorts(ports: (string | number)[]): number[] {
  const seen = new Set<number>()
  const result: number[] = []
  let invalid = 0
  for (const p of ports) {
    const n = Number(p)
    if (!Number.isInteger(n) || n < 1 || n > 65535) {
      invalid++
      continue
    }
    if (!seen.has(n)) {
      seen.add(n)
      result.push(n)
    }
  }
  if (invalid > 0) {
    message.warning(`已忽略 ${invalid} 个无效端口（端口须为 1-65535 的整数）`)
  }
  return result
}

// 登记私有仓库镜像（D3）：创建记录（未推送），随后经"上传"弹窗命令自行推送
async function handleRegister(): Promise<void> {
  if (!registerForm.name.trim()) {
    message.warning('请填写原始镜像名')
    return
  }
  submitting.value = true
  try {
    const result = await imageApi.register({
      name: registerForm.name.trim(),
      tag: registerForm.tag.trim() || 'latest',
      appPorts: registerForm.appPorts.length ? registerForm.appPorts : undefined,
      usageInstructions: registerForm.usageInstructions || undefined,
      mountPoint: registerForm.mountPoint || undefined,
    })
    message.success(`镜像登记成功：${result.name}:${result.tag}，请在本机执行推送命令后点击"刷新状态"`)
    registerVisible.value = false
    load()
  } catch {
    // 拦截器已提示
  } finally {
    submitting.value = false
  }
}

// 上传命令弹窗（D3）：数据来自 push-commands 接口，前端不硬编码仓库地址
async function openPushCommands(image: ImageMetadata): Promise<void> {
  pushCmds.value = await imageApi.pushCommands(image.id)
  pushCmdVisible.value = true
}

async function copyText(text: string): Promise<void> {
  const ok = await copyToClipboard(text)
  if (ok) {
    message.success('已复制到剪贴板')
  } else {
    message.error('复制失败，请手动选择复制')
  }
}

// 刷新状态（D3）：即时重新检查仓库有效性并更新该行
async function refreshValidity(image: ImageMetadata): Promise<void> {
  try {
    const updated = await imageApi.refreshValidity(image.id)
    const target = images.value.find((i) => i.id === image.id)
    if (target) {
      target.registryValid = updated.registryValid
      target.registryCheckedAt = updated.registryCheckedAt
      target.status = updated.status
    }
    message.success(
      updated.registryValid
        ? `镜像已推送到仓库（有效）：${updated.name}:${updated.tag}`
        : `仓库中未找到该镜像（无效）：${updated.name}:${updated.tag}`,
    )
  } catch {
    // 拦截器已提示（仓库不可达时不改变原结论）
  }
}

// ===== 无标记镜像（D3，仅管理员）=====

async function loadUntagged(): Promise<void> {
  untaggedLoading.value = true
  try {
    untagged.value = await imageApi.listUntagged()
  } catch {
    // 拦截器已提示
  } finally {
    untaggedLoading.value = false
  }
}

// 切到无标记镜像 tab 时加载
function onTabChange(key: string): void {
  if (key === 'untagged') loadUntagged()
}

function openClaim(item: UntaggedImage, tag: string): void {
  claimForm.repo = item.repo
  claimForm.tag = tag
  claimForm.appPorts = []
  claimForm.usageInstructions = ''
  claimForm.mountPoint = ''
  claimForm.visibility = 'SHARED_TO_ALL'
  claimVisible.value = true
}

function claimPortChange(ports: (string | number)[]): void {
  claimForm.appPorts = dedupePorts(ports)
}

// 补录（D3）：以仓库 repo 名为镜像名建记录（READY+有效），按可见性对用户可用
async function handleClaim(): Promise<void> {
  claimSubmitting.value = true
  try {
    const result = await imageApi.claimUntagged({
      repo: claimForm.repo,
      tag: claimForm.tag,
      appPorts: claimForm.appPorts.length ? claimForm.appPorts : undefined,
      usageInstructions: claimForm.usageInstructions || undefined,
      mountPoint: claimForm.mountPoint || undefined,
      visibility: claimForm.visibility,
    })
    message.success(`补录成功：${result.name}:${result.tag} 已可用于创建容器`)
    claimVisible.value = false
    load()
    loadUntagged()
  } catch {
    // 拦截器已提示
  } finally {
    claimSubmitting.value = false
  }
}

async function openShare(image: ImageMetadata): Promise<void> {
  shareImage.value = image
  // 按当前可见性预选模式
  shareForm.mode = image.visibility === 'SHARED_TO_ALL' ? 'all' : 'worker'
  shareForm.targetWorkerIds = []
  shareForm.targetGroupId = undefined
  shareVisible.value = true
  // 课题组选项：admin 可见全部，mentor 仅本组（platform-refinements 6.8）
  try {
    groups.value = await groupApi.list()
  } catch {
    const my = await groupApi.my().catch(() => null)
    groups.value = my ? [my] : []
  }
}

async function handleShare(): Promise<void> {
  if (!shareImage.value) return
  try {
    if (shareForm.mode === 'all') {
      await imageApi.setVisibility(shareImage.value.id, 'SHARED_TO_ALL')
    } else if (shareForm.mode === 'private') {
      await imageApi.setVisibility(shareImage.value.id, 'PRIVATE')
    } else if (shareForm.mode === 'worker') {
      if (!shareForm.targetWorkerIds.length) {
        message.warning('请输入工号')
        return
      }
      await imageApi.share(shareImage.value.id, { targetWorkerIds: shareForm.targetWorkerIds })
    } else {
      if (!shareForm.targetGroupId) {
        message.warning('请选择课题组')
        return
      }
      await imageApi.share(shareImage.value.id, { targetGroupId: shareForm.targetGroupId })
    }
    message.success('已更新镜像权限')
    shareVisible.value = false
    load()
  } catch {
    // 拦截器已提示
  }
}

function formatSize(bytes?: number): string {
  if (!bytes) return '-'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  if (bytes < 1024 * 1024 * 1024) return (bytes / 1024 / 1024).toFixed(1) + ' MB'
  return (bytes / 1024 / 1024 / 1024).toFixed(2) + ' GB'
}

// 有效性列文案（D3）：TAR 镜像显示"-"（不检查）
function validityLabel(image: ImageMetadata): { text: string; color: string } {
  if (image.distribution !== 'REGISTRY') return { text: '-', color: 'default' }
  if (image.registryValid === true) return { text: '有效', color: 'green' }
  if (image.registryValid === false) return { text: '无效', color: 'red' }
  return { text: '未检查', color: 'orange' }
}

function confirmDelete(image: ImageMetadata): void {
  Modal.confirm({
    title: `确认删除镜像 ${image.name}:${image.tag}？`,
    content: image.distribution === 'REGISTRY'
      ? '仅删除系统内记录，私有仓库中的镜像不受影响'
      : '删除后不可恢复',
    onOk: async () => {
      await imageApi.delete(image.id)
      message.success('镜像已删除')
      load()
    },
  })
}

async function downloadImage(image: ImageMetadata): Promise<void> {
  try {
    const blob = await imageApi.download(image.id)
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `${image.name}-${image.tag}.tar`
    a.click()
    URL.revokeObjectURL(url)
  } catch {
    // 拦截器已提示
  }
}
</script>

<template>
  <div>
    <div style="display: flex; justify-content: space-between; margin-bottom: 16px">
      <a-typography-title :level="3" style="margin: 0">镜像管理</a-typography-title>
      <a-button type="primary" @click="showRegister">登记镜像</a-button>
    </div>

    <a-tabs v-model:active-key="activeTab" @change="onTabChange">
      <!-- 镜像列表 -->
      <a-tab-pane key="list" tab="镜像列表">
        <a-table :data-source="images" :loading="loading" row-key="id" :pagination="false">
          <a-table-column title="镜像名" data-index="name" />
          <a-table-column title="标签" data-index="tag" :width="100" />
          <a-table-column title="归属" :width="120">
            <template #default="{ record }">
              <a-tag v-if="record.isPublic" color="purple">公共</a-tag>
              <a-tag v-else-if="record.visibility === 'SHARED_TO_ALL'" color="geekblue">全员</a-tag>
              <span v-else>{{ record.ownerName ?? '-' }}</span>
            </template>
          </a-table-column>
          <a-table-column title="分发" :width="80">
            <template #default="{ record }">
              <a-tag :color="record.distribution === 'REGISTRY' ? 'blue' : 'default'">
                {{ record.distribution === 'REGISTRY' ? '仓库' : 'tar' }}
              </a-tag>
            </template>
          </a-table-column>
          <a-table-column title="有效性" :width="90">
            <template #default="{ record }">
              <a-tag :color="validityLabel(record).color">{{ validityLabel(record).text }}</a-tag>
            </template>
          </a-table-column>
          <a-table-column title="大小" :width="100">
            <template #default="{ record }">{{ formatSize(record.sizeBytes) }}</template>
          </a-table-column>
          <a-table-column title="来源" :width="110">
            <template #default="{ record }">
              <a-tag v-if="record.sourceContainer === 'tar-upload'" color="cyan">tar上传</a-tag>
              <a-tag v-else-if="record.sourceContainer === 'registry-register'" color="blue">登记</a-tag>
              <a-tag v-else-if="record.sourceContainer === 'registry-claim'" color="purple">补录</a-tag>
              <a-tag v-else-if="record.sourceContainer === 'dockerfile-build'" color="blue">Dockerfile</a-tag>
              <a-tag v-else-if="record.sourceContainer" color="green">commit</a-tag>
              <span v-else>-</span>
            </template>
          </a-table-column>
          <a-table-column title="应用端口" :width="160">
            <template #default="{ record }">
              <template v-if="record.appPorts && record.appPorts.length">
                <a-tag v-for="p in record.appPorts" :key="p" color="blue">{{ p }}</a-tag>
              </template>
              <span v-else style="color: #ccc">-</span>
            </template>
          </a-table-column>
          <a-table-column title="容器内挂载" :width="160">
            <template #default="{ record }">
              <a-tooltip v-if="record.mountPoint" :title="record.mountPoint">
                <a-tag color="geekblue">{{ record.mountPoint }}</a-tag>
              </a-tooltip>
              <span v-else style="color: #ccc">-</span>
            </template>
          </a-table-column>
          <a-table-column title="使用说明" :width="200">
            <template #default="{ record }">
              <a-tooltip v-if="record.usageInstructions" :title="record.usageInstructions">
                <span style="display: inline-block; max-width: 180px" class="ellipsis">{{ record.usageInstructions }}</span>
              </a-tooltip>
              <span v-else style="color: #ccc">-</span>
            </template>
          </a-table-column>
          <a-table-column title="状态" :width="90">
            <template #default="{ record }">
              <a-tag :color="record.status === 'READY' ? 'green' : record.status === 'FAILED' ? 'red' : 'orange'">
                {{ record.status === 'READY' ? '就绪' : record.status === 'UPLOADING' ? '推送中' : record.status === 'FAILED' ? '失败' : record.status }}
              </a-tag>
            </template>
          </a-table-column>
          <a-table-column title="创建时间" :width="160">
            <template #default="{ record }">{{ dayjs(record.createdAt).format('YYYY-MM-DD HH:mm') }}</template>
          </a-table-column>
          <a-table-column title="操作" :width="420">
            <template #default="{ record }">
              <a-button v-if="canOperate(record)" type="link" size="small" @click="openEdit(record)">编辑</a-button>
              <a-button
                v-if="record.distribution === 'REGISTRY' && canOperate(record)"
                type="link" size="small"
                @click="openPushCommands(record)"
              >上传</a-button>
              <a-button
                v-if="record.distribution === 'REGISTRY' && canOperate(record)"
                type="link" size="small"
                @click="refreshValidity(record)"
              >刷新状态</a-button>
              <a-button
                v-if="record.distribution === 'REGISTRY' && record.registryValid && isAdmin"
                type="link" size="small"
                @click="openSyncDrawer(record)"
              >同步到所有机器</a-button>
              <a-button
                v-if="record.distribution !== 'REGISTRY' && record.tarPath"
                type="link" size="small"
                @click="downloadImage(record)"
              >下载</a-button>
              <a-button v-if="canOperate(record)" type="link" size="small" @click="openShare(record)">{{ shareLabel }}</a-button>
              <a-button
                v-if="canOperate(record)"
                type="link" size="small" danger @click="confirmDelete(record)"
              >删除</a-button>
            </template>
          </a-table-column>
        </a-table>
      </a-tab-pane>

      <!-- 无标记镜像（registry-image-distribution D3，仅管理员） -->
      <a-tab-pane v-if="isAdmin" key="untagged" tab="无标记镜像">
        <a-alert
          type="info" show-icon style="margin-bottom: 12px"
          message="列出了私有仓库中存在但系统内没有登记记录的镜像。补录元数据后即可对用户开放使用。"
        />
        <a-table :data-source="untagged" :loading="untaggedLoading" row-key="repo" :pagination="false">
          <a-table-column title="镜像名（仓库 repo）" data-index="repo" />
          <a-table-column title="未登记标签" :width="320">
            <template #default="{ record }">
              <a-tag v-for="t in record.tags" :key="t" color="blue" style="margin: 2px">{{ t }}</a-tag>
            </template>
          </a-table-column>
          <a-table-column title="操作" :width="160">
            <template #default="{ record }">
              <a-button
                v-for="t in record.tags" :key="t"
                type="link" size="small"
                @click="openClaim(record, t)"
              >登记 {{ t }}</a-button>
            </template>
          </a-table-column>
        </a-table>
      </a-tab-pane>
    </a-tabs>

    <!-- 登记镜像对话框（registry-image-distribution D3：纯元数据，无文件上传） -->
    <a-modal
      v-model:open="registerVisible"
      title="登记镜像"
      width="700px"
      :confirm-loading="submitting"
      @ok="handleRegister"
    >
      <a-form layout="vertical">
        <a-form-item label="原始镜像名" required>
          <a-input v-model:value="registerForm.name" placeholder="如 lab404-jupyter（须为 docker 合法小写镜像名）" />
        </a-form-item>
        <a-form-item label="原始标签">
          <a-input v-model:value="registerForm.tag" placeholder="如 0.1（默认 latest）" />
        </a-form-item>
        <a-form-item label="应用端口（多个，可留空；创建容器时预填容器内端口）">
          <a-select
            :value="registerForm.appPorts"
            mode="tags"
            placeholder="如 8888, 6006"
            @change="registerPortChange"
          />
        </a-form-item>
        <a-form-item label="容器内挂载">
          <a-input
            v-model:value="registerForm.mountPoint"
            placeholder="如 /workspace（存储池映射到容器内的路径，创建容器时自动预填）"
          />
        </a-form-item>
        <a-form-item label="使用说明">
          <a-textarea
            v-model:value="registerForm.usageInstructions"
            :rows="2"
            placeholder="镜像用途、启动方式等说明（可选）"
          />
        </a-form-item>
        <a-alert
          type="info" show-icon
          message="登记后需在本机执行推送命令（docker tag + docker push）将镜像推送至私有仓库，再点击列表中的「刷新状态」确认有效。"
        />
      </a-form>
    </a-modal>

    <!-- 上传命令对话框（D3：操作列"上传"按钮） -->
    <a-modal
      :open="pushCmdVisible"
      title="推送镜像到私有仓库"
      :footer="null"
      @cancel="pushCmdVisible = false"
    >
      <template v-if="pushCmds">
        <p style="color: #666">
          在本机（已有该镜像的机器）依次执行以下命令完成推送，全部推送完成后回到列表点击"刷新状态"：
        </p>
        <div v-for="cmd in [pushCmds.tagCmd, pushCmds.pushCmd]" :key="cmd" style="position: relative; margin-bottom: 12px">
          <pre style="background: #1e1e1e; color: #d4d4d4; padding: 12px; border-radius: 4px; font-size: 12px; margin: 0; padding-right: 72px; overflow-x: auto">{{ cmd }}</pre>
          <a-button
            size="small" type="primary"
            style="position: absolute; top: 10px; right: 10px"
            @click="copyText(cmd)"
          >复制</a-button>
        </div>
        <a-alert
          type="warning" show-icon
          :message="`若本机 Docker 未配置私有仓库（${pushCmds.registryUrl}）为 insecure-registry，push 会失败，请先在 Docker daemon 配置 insecure-registries 并重启 Docker。`"
        />
      </template>
    </a-modal>

    <!-- 共享/权限对话框（platform-refinements 6.8；管理员为权限管理） -->
    <a-modal v-model:open="shareVisible" :title="shareLabel === '权限' ? '镜像权限' : '共享镜像'" @ok="handleShare">
      <a-form layout="vertical">
        <a-form-item label="权限范围">
          <a-radio-group v-model:value="shareForm.mode">
            <a-radio value="all">全员可见</a-radio>
            <a-radio value="worker">按工号</a-radio>
            <a-radio value="group">课题组</a-radio>
            <a-radio value="private">仅本人</a-radio>
          </a-radio-group>
        </a-form-item>
        <a-form-item v-if="shareForm.mode === 'worker'" label="目标用户工号（可多个）">
          <a-select
            v-model:value="shareForm.targetWorkerIds"
            mode="tags"
            :token-separators="[',']"
            placeholder="输入工号后回车，支持多个"
          />
        </a-form-item>
        <a-form-item v-else-if="shareForm.mode === 'group'" label="目标课题组">
          <a-select
            v-model:value="shareForm.targetGroupId"
            placeholder="选择课题组（全体成员可见）"
          >
            <a-select-option v-for="g in groups" :key="g.id" :value="g.id">{{ g.name }}</a-select-option>
          </a-select>
        </a-form-item>
        <a-alert
          v-else-if="shareForm.mode === 'all'"
          type="info" show-icon
          message="所有用户均可在镜像列表看到并使用该镜像创建容器（不自动同步至受控端，创建时按需拉取/导入）。"
        />
        <a-alert
          v-else-if="shareForm.mode === 'private'"
          type="warning" show-icon
          message="仅本人与管理员可见，已共享关系不清除但即时失效（恢复全员/共享后再次生效）。"
        />
      </a-form>
    </a-modal>

    <!-- 编辑应用端口、使用说明与容器内挂载点 -->
    <a-modal
      v-model:open="editVisible"
      title="编辑应用端口、使用说明与容器内挂载"
      :confirm-loading="editSubmitting"
      @ok="handleEditSubmit"
    >
      <a-form layout="vertical">
        <a-form-item label="镜像">
          <a-input :value="`${editForm.name}:${editForm.tag}`" disabled />
        </a-form-item>
        <a-form-item label="应用端口（多个，创建容器时预填容器内端口）">
          <a-select
            :value="editForm.appPorts"
            mode="tags"
            placeholder="如 8888, 6006"
            @change="editPortChange"
          />
        </a-form-item>
        <a-form-item label="使用说明">
          <a-textarea
            v-model:value="editForm.usageInstructions"
            :rows="4"
            placeholder="镜像用途、启动方式、访问方式等说明"
          />
        </a-form-item>
        <a-form-item label="容器内挂载">
          <a-input
            v-model:value="editForm.mountPoint"
            placeholder="如 /workspace（存储池映射到容器内的路径，创建容器时自动预填）"
          />
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- 无标记镜像补录（D3，仅管理员） -->
    <a-modal
      v-model:open="claimVisible"
      title="补录无标记镜像"
      :confirm-loading="claimSubmitting"
      @ok="handleClaim"
    >
      <a-form layout="vertical">
        <a-form-item label="镜像（仓库 repo:tag）">
          <a-input :value="`${claimForm.repo}:${claimForm.tag}`" disabled />
        </a-form-item>
        <a-form-item label="应用端口（多个，可留空；创建容器时预填容器内端口）">
          <a-select
            :value="claimForm.appPorts"
            mode="tags"
            placeholder="如 8888, 6006"
            @change="claimPortChange"
          />
        </a-form-item>
        <a-form-item label="容器内挂载">
          <a-input
            v-model:value="claimForm.mountPoint"
            placeholder="如 /workspace（存储池映射到容器内的路径，创建容器时自动预填）"
          />
        </a-form-item>
        <a-form-item label="使用说明">
          <a-textarea
            v-model:value="claimForm.usageInstructions"
            :rows="3"
            placeholder="镜像用途、启动方式、访问方式等说明"
          />
        </a-form-item>
        <a-form-item label="可见性">
          <a-radio-group v-model:value="claimForm.visibility">
            <a-radio value="SHARED_TO_ALL">全员可见</a-radio>
            <a-radio value="PRIVATE">仅本人</a-radio>
          </a-radio-group>
        </a-form-item>
        <a-alert
          type="info" show-icon
          message="补录后镜像立即有效（READY），按所设可见性对用户开放，可用于创建容器。"
        />
      </a-form>
    </a-modal>
    <!-- 同步到所有机器抽屉（V36，仅管理员）：批次头计数 + 每实例进度表，SSE 实时更新 -->
    <a-drawer v-model:open="syncDrawerVisible" title="镜像同步到所有机器" width="720">
      <template v-if="syncView">
        <a-descriptions :column="2" size="small" style="margin-bottom: 12px">
          <a-descriptions-item label="镜像">{{ syncView.batch.imageRef }}</a-descriptions-item>
          <a-descriptions-item label="批次状态">
            <a-tag :color="syncView.batch.status === 'RUNNING' ? 'processing' : 'green'">
              {{ syncView.batch.status === 'RUNNING' ? '进行中' : '已完成' }}
            </a-tag>
          </a-descriptions-item>
          <a-descriptions-item label="汇总">
            共 {{ syncView.total }} 台：完成 {{ syncView.pulled }} / 已存在 {{ syncView.alreadyExists }} /
            失败 {{ syncView.failed }} / 离线跳过 {{ syncView.offlineSkipped }}
          </a-descriptions-item>
          <a-descriptions-item label="发起时间">
            {{ dayjs(syncView.batch.createdAt).format('YYYY-MM-DD HH:mm:ss') }}
          </a-descriptions-item>
        </a-descriptions>
        <a-table :data-source="syncView.tasks" row-key="id" :pagination="false" size="small">
          <a-table-column title="机器" data-index="instanceNumber" :width="120" />
          <a-table-column title="状态" :width="110">
            <template #default="{ record }">
              <a-tag :color="syncStatusLabel(record.status).color">{{ syncStatusLabel(record.status).text }}</a-tag>
            </template>
          </a-table-column>
          <a-table-column title="进度" :width="200">
            <template #default="{ record }">
              <a-progress
                v-if="record.status === 'PULLING' || record.status === 'PULLED'"
                :percent="record.percent ?? 0"
                size="small"
              />
              <span v-else style="color: #ccc">-</span>
            </template>
          </a-table-column>
          <a-table-column title="详情">
            <template #default="{ record }">
              <a-tooltip v-if="record.errorMessage" :title="record.errorMessage">
                <span style="color: #ff4d4f" class="sync-detail ellipsis">{{ record.errorMessage }}</span>
              </a-tooltip>
              <a-tooltip v-else-if="record.lastText" :title="record.lastText">
                <span class="sync-detail ellipsis">{{ record.lastText }}</span>
              </a-tooltip>
              <span v-else style="color: #ccc">-</span>
            </template>
          </a-table-column>
        </a-table>
      </template>
    </a-drawer>
  </div>
</template>

<style scoped>
.ellipsis {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.sync-detail {
  display: inline-block;
  max-width: 260px;
}
</style>
