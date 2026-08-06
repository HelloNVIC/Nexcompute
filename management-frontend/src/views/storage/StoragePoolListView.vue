<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { message, Modal } from 'ant-design-vue'
import dayjs from 'dayjs'
import {
  storagePoolApi,
  type StoragePool,
  type RevokeShareResult,
  type PoolFileEntry,
} from '@/api/storagePool'
import { instanceApi, type PhysicalInstance } from '@/api/instance'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const isAdmin = auth.role === 'ADMIN'

const loading = ref(false)
const pools = ref<StoragePool[]>([])
const instances = ref<PhysicalInstance[]>([])

const createVisible = ref(false)
const createForm = reactive({ instanceId: undefined as number | undefined, projectName: '' })

const migrateVisible = ref(false)
const migrateForm = reactive({ poolId: 0, targetInstanceId: undefined as number | undefined })
// 迁移：记录源池所在物理机，目标实例下拉排除同一物理机
const migrateSourceInstanceId = ref<number | undefined>()

// 文件管理（platform-refinements #3）
const filesVisible = ref(false)
const filePool = ref<StoragePool | null>(null)
const currentPath = ref<string[]>([])
const files = ref<PoolFileEntry[]>([])
const filesLoading = ref(false)
const uploading = ref(false)
// platform-refinements #7：上传进度
const uploadProgress = ref({ current: 0, total: 0, name: '' })
let uploadInput: HTMLInputElement | null = null

// 下载打包进度（platform-refinements #3：异步回传进度条）
const dlProgress = ref({ visible: false, percent: 0, name: '', status: '' })
let dlTimer: ReturnType<typeof setInterval> | null = null

onMounted(load)

async function load(): Promise<void> {
  loading.value = true
  try {
    pools.value = await storagePoolApi.list()
    instances.value = await instanceApi.list()
  } catch {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

async function handleCreate(): Promise<void> {
  if (!createForm.instanceId || !createForm.projectName) {
    message.warning('请填写完整')
    return
  }
  try {
    await storagePoolApi.create({
      instanceId: createForm.instanceId!,
      projectName: createForm.projectName,
    })
    message.success('存储池已创建')
    createVisible.value = false
    createForm.projectName = ''
    load()
  } catch {
    // 拦截器已提示
  }
}

function showMigrate(pool: StoragePool): void {
  migrateForm.poolId = pool.id
  migrateForm.targetInstanceId = undefined
  migrateSourceInstanceId.value = pool.instanceId
  migrateVisible.value = true
}

async function handleMigrate(): Promise<void> {
  if (!migrateForm.targetInstanceId) {
    message.warning('请选择目标实例')
    return
  }
  try {
    await storagePoolApi.migrate(migrateForm.poolId, migrateForm.targetInstanceId)
    message.success('迁移已启动')
    migrateVisible.value = false
    load()
  } catch {
    // 拦截器已提示
  }
}

function confirmMigrate(pool: StoragePool): void {
  Modal.confirm({
    title: '确认删除源池数据？',
    content: '迁移已完成，确认后将删除源物理实例上的原存储池数据',
    onOk: async () => {
      await storagePoolApi.confirmMigration(pool.id)
      message.success('迁移已确认')
      load()
    },
  })
}

// 删除存储池（platform-refinements 7.3）：二次确认，后端前置检查无运行容器依赖
// 文件管理（platform-refinements #3）：浏览/打包下载/上传
async function openFiles(pool: StoragePool): Promise<void> {
  if (pool.offline) {
    message.warning('存储池离线，无法浏览文件')
    return
  }
  filePool.value = pool
  currentPath.value = []
  filesVisible.value = true
  await loadFiles()
}

const pathStr = () => currentPath.value.join('/')

async function loadFiles(): Promise<void> {
  if (!filePool.value) return
  filesLoading.value = true
  try {
    files.value = await storagePoolApi.files(filePool.value.id, pathStr())
  } catch {
    files.value = []
  } finally {
    filesLoading.value = false
  }
}

function enterDir(name: string): void {
  currentPath.value.push(name)
  loadFiles()
}

function gotoSegment(idx: number): void {
  currentPath.value = currentPath.value.slice(0, idx + 1)
  loadFiles()
}

function backToRoot(): void {
  currentPath.value = []
  loadFiles()
}

async function downloadEntry(entry: PoolFileEntry): Promise<void> {
  if (!filePool.value) return
  const sub = currentPath.value.concat(entry.name).join('/')
  const transferId = (await storagePoolApi.downloadStart(filePool.value.id, sub)).transferId
  // 异步轮询回传进度（platform-refinements #3）
  dlProgress.value = { visible: true, percent: 0, name: entry.name, status: '打包中…' }
  let attempts = 0
  await new Promise<void>((resolve) => {
    dlTimer = setInterval(async () => {
      attempts += 1
      try {
        const p = await storagePoolApi.downloadProgress(transferId)
        dlProgress.value.percent = p.percent
        dlProgress.value.status = p.percent > 0 ? `回传中 ${p.percent}%` : '打包中…'
        if (p.status === 'completed') {
          if (dlTimer) { clearInterval(dlTimer); dlTimer = null }
          dlProgress.value.status = '回传完成，下载中…'
          const blob = await storagePoolApi.downloadFile(transferId)
          const url = URL.createObjectURL(blob)
          const a = document.createElement('a')
          a.href = url
          a.download = entry.name + (entry.isDir ? '.tar' : '')
          a.click()
          URL.revokeObjectURL(url)
          dlProgress.value.visible = false
          resolve()
        } else if (p.status === 'checksum_failed' || p.status === 'failed') {
          if (dlTimer) { clearInterval(dlTimer); dlTimer = null }
          message.error('打包/回传失败')
          dlProgress.value.visible = false
          resolve()
        }
      } catch {
        // 单次查询失败忽略
      }
      if (attempts > 600) { // 超时 ~10min
        if (dlTimer) { clearInterval(dlTimer); dlTimer = null }
        message.error('下载超时')
        dlProgress.value.visible = false
        resolve()
      }
    }, 1000)
  })
}

function pickUploadFiles(dir: boolean): void {
  if (!filePool.value) return
  if (uploadInput) document.body.removeChild(uploadInput)
  uploadInput = document.createElement('input')
  uploadInput.type = 'file'
  if (dir) {
    uploadInput.setAttribute('webkitdirectory', '')
    uploadInput.multiple = true
  } else {
    uploadInput.multiple = true
  }
  uploadInput.onchange = async () => {
    const list = uploadInput?.files
    if (!list || !list.length || !filePool.value) return
    uploading.value = true
    uploadProgress.value = { current: 0, total: list.length, name: '' }
    try {
      const arr = Array.from(list)
      for (let i = 0; i < arr.length; i++) {
        const f = arr[i]
        const rel = (f as File & { webkitRelativePath?: string }).webkitRelativePath || f.name
        uploadProgress.value = { current: i, total: arr.length, name: rel }
        await storagePoolApi.uploadFile(filePool.value.id, pathStr(), rel, f)
      }
      uploadProgress.value = { current: arr.length, total: arr.length, name: '' }
      message.success(`已上传 ${arr.length} 个文件`)
      await loadFiles()
    } catch {
      // 拦截器已提示
    } finally {
      uploading.value = false
      uploadProgress.value = { current: 0, total: 0, name: '' }
    }
  }
  uploadInput.click()
}

function confirmDelete(pool: StoragePool): void {
  Modal.confirm({
    title: `确认删除存储池 ${pool.poolName}？`,
    content: '删除后不可恢复；若该池有运行中容器将被拒绝，请先停止相关容器。',
    onOk: async () => {
      try {
        await storagePoolApi.remove(pool.id)
        message.success('存储池已删除')
        load()
      } catch {
        // 拦截器已提示（如存在运行容器依赖）
      }
    },
  })
}

// 物理实例离线时管理员强制删除：三次确认（不可恢复，磁盘数据需手动清理）
function confirmForceDelete(pool: StoragePool, step = 1): void {
  const steps = [
    {
      title: `第 1/3 步：强制删除存储池「${pool.poolName}」？`,
      content: '所属物理实例不在线。强制删除仅清除管理端元数据与共享/迁移关系，不会清理受控端磁盘数据（需实例恢复后手动清理）。',
      okText: '继续',
    },
    {
      title: '第 2/3 步：再次确认',
      content: '此操作不可恢复。确定要继续强制删除该存储池吗？',
      okText: '继续',
    },
    {
      title: '第 3/3 步：最后确认',
      content: '这是最后一次确认。确定强制删除存储池？',
      okText: '强制删除',
    },
  ]
  const s = steps[step - 1]
  Modal.confirm({
    title: s.title,
    content: s.content,
    okText: s.okText,
    okType: 'danger',
    cancelText: '取消',
    onOk: async () => {
      if (step < 3) {
        confirmForceDelete(pool, step + 1)
        return
      }
      try {
        await storagePoolApi.remove(pool.id, true)
        message.success('存储池已强制删除')
        load()
      } catch {
        // 拦截器已提示
      }
    },
  })
}

const statusLabel: Record<string, string> = {
  ACTIVE: '正常', MIGRATING: '迁移中', MIGRATED: '已迁移',
}
const statusColor: Record<string, string> = {
  ACTIVE: 'green', MIGRATING: 'orange', MIGRATED: 'blue',
}

function formatFileSize(bytes: number): string {
  if (!bytes) return '-'
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB'
  if (bytes < 1024 * 1024 * 1024) return (bytes / 1024 / 1024).toFixed(1) + ' MB'
  return (bytes / 1024 / 1024 / 1024).toFixed(2) + ' GB'
}
</script>

<template>
  <div>
    <div style="display: flex; justify-content: space-between; margin-bottom: 16px">
      <a-typography-title :level="3" style="margin: 0">存储池</a-typography-title>
      <a-button type="primary" @click="createVisible = true">创建存储池</a-button>
    </div>

    <a-table :data-source="pools" :loading="loading" row-key="id" :pagination="false" :scroll="{ x: 'max-content' }" class="auto-table">
      <a-table-column title="存储池名称" data-index="poolName" :sorter="(a: StoragePool, b: StoragePool) => (a.poolName||'').localeCompare(b.poolName||'')" />
      <a-table-column title="物理机编号" data-index="instanceNumber" :width="100" :sorter="(a: StoragePool, b: StoragePool) => (a.instanceNumber||'').localeCompare(b.instanceNumber||'')" />
      <a-table-column title="绝对路径" :width="280">
        <template #default="{ record }">
          <a-tooltip :title="record.poolPath">
            <a-typography-text style="font-family: monospace; font-size: 12px" copyable>
              {{ record.poolPath || '-' }}
            </a-typography-text>
          </a-tooltip>
        </template>
      </a-table-column>
      <a-table-column title="状态" :width="120" :sorter="(a: StoragePool, b: StoragePool) => (a.status||'').localeCompare(b.status||'')">
        <template #default="{ record }">
          <a-tag :color="statusColor[record.status]">{{ statusLabel[record.status] }}</a-tag>
          <a-tag v-if="record.offline" color="red">离线</a-tag>
        </template>
      </a-table-column>
      <a-table-column title="创建时间" :width="170" :sorter="(a: StoragePool, b: StoragePool) => (a.createdAt||'').localeCompare(b.createdAt||'')">
        <template #default="{ record }">{{ dayjs(record.createdAt).format('YYYY-MM-DD HH:mm') }}</template>
      </a-table-column>
      <a-table-column title="操作" :width="360">
        <template #default="{ record }">
          <a-tooltip :title="record.offline ? '物理实例不在线，操作不可用' : ''">
            <span>
              <a-button type="link" size="small" :disabled="record.offline" @click="openFiles(record)">文件</a-button>
              <a-button type="link" size="small" :disabled="record.offline" @click="showMigrate(record)">迁移</a-button>
              <a-button
                v-if="record.status === 'MIGRATING'" type="link" size="small"
                :disabled="record.offline"
                @click="confirmMigrate(record)"
              >确认删除</a-button>
            </span>
          </a-tooltip>
          <a-button
            v-if="record.offline && isAdmin"
            type="link" size="small" danger @click="confirmForceDelete(record)"
          >强制删除</a-button>
          <a-button
            v-else type="link" size="small" danger :disabled="record.offline" @click="confirmDelete(record)"
          >删除</a-button>
        </template>
      </a-table-column>
    </a-table>

    <a-modal v-model:open="createVisible" title="创建存储池" @ok="handleCreate">
      <a-form layout="vertical">
        <a-form-item label="物理实例">
          <a-select v-model:value="createForm.instanceId" placeholder="选择物理实例">
            <a-select-option v-for="i in instances" :key="i.id" :value="i.id" :disabled="!i.storageRoot">
              {{ i.instanceNumber }} - {{ i.machineName }}{{ i.storageRoot ? '' : '（未设存储池根目录）' }}
            </a-select-option>
          </a-select>
          <a-typography-text v-if="instances.some((i) => !i.storageRoot)" type="secondary" style="font-size: 12px">
            仅可在已设置存储池根目录的实例上建池；未设置的请先在受控端 GUI 设置根目录。
          </a-typography-text>
        </a-form-item>
        <a-form-item label="项目名">
          <a-input v-model:value="createForm.projectName" placeholder="如 bert-finetune" />
          <a-typography-text type="secondary" style="font-size: 12px">
            存储池命名格式：物理机编号-工号/学号-项目名
          </a-typography-text>
        </a-form-item>
      </a-form>
    </a-modal>

    <a-modal v-model:open="migrateVisible" title="迁移存储池" @ok="handleMigrate">
      <a-form layout="vertical">
        <a-form-item label="目标物理实例">
          <a-select v-model:value="migrateForm.targetInstanceId" placeholder="选择目标实例">
            <a-select-option
              v-for="i in instances.filter((x) => x.id !== migrateSourceInstanceId)"
              :key="i.id"
              :value="i.id"
              :disabled="!i.storageRoot"
            >
              {{ i.instanceNumber }} - {{ i.machineName }}{{ i.storageRoot ? '' : '（未设存储池根目录）' }}
            </a-select-option>
          </a-select>
        </a-form-item>
        <a-alert type="info" message="迁移前置：无运行容器使用此存储池" show-icon />
      </a-form>
    </a-modal>

    <!-- 下载打包进度（platform-refinements #3） -->
    <a-modal
      v-model:open="dlProgress.visible"
      :closable="false"
      :maskClosable="false"
      :footer="null"
      :keyboard="false"
      width="420px"
      title="下载打包"
    >
      <div style="text-align: center; padding: 16px 0">
        <a-progress :percent="dlProgress.percent" status="active" />
        <p style="margin-top: 16px">{{ dlProgress.name }}</p>
        <p style="color: #999; font-size: 12px">{{ dlProgress.status }}</p>
      </div>
    </a-modal>

    <!-- 文件管理（platform-refinements #3） -->
    <a-modal
      v-model:open="filesVisible"
      :title="'存储池文件：' + (filePool?.poolName ?? '')"
      width="820px"
      :footer="null"
    >
      <div style="margin-bottom: 12px; display: flex; justify-content: space-between; align-items: center">
        <a-space>
          <a-button size="small" @click="backToRoot">根目录</a-button>
          <a-breadcrumb>
            <a-breadcrumb-item @click="backToRoot" style="cursor: pointer">/</a-breadcrumb-item>
            <a-breadcrumb-item v-for="(seg, i) in currentPath" :key="i" @click="gotoSegment(i)" style="cursor: pointer">
              {{ seg }}
            </a-breadcrumb-item>
          </a-breadcrumb>
        </a-space>
        <a-space>
          <a-button size="small" :loading="uploading" @click="pickUploadFiles(false)">上传文件</a-button>
          <a-button size="small" :loading="uploading" @click="pickUploadFiles(true)">上传文件夹</a-button>
        </a-space>
      </div>
      <div v-if="uploading" style="margin-bottom: 12px">
        <a-progress
          :percent="uploadProgress.total ? Math.round((uploadProgress.current / uploadProgress.total) * 100) : 0"
          status="active"
        />
        <div style="font-size: 12px; color: #999; margin-top: 4px">
          上传中 {{ uploadProgress.current }}/{{ uploadProgress.total }}
          <span v-if="uploadProgress.name">- {{ uploadProgress.name }}</span>
        </div>
      </div>
      <a-table :data-source="files" :loading="filesLoading" row-key="name" :pagination="false" size="small">
        <a-table-column title="名称" data-index="name">
          <template #default="{ record }">
            <a v-if="record.isDir" @click="enterDir(record.name)" style="cursor: pointer">📁 {{ record.name }}</a>
            <span v-else>📄 {{ record.name }}</span>
          </template>
        </a-table-column>
        <a-table-column title="大小" :width="100">
          <template #default="{ record }">
            <span v-if="record.isDir" style="color: #999">-</span>
            <span v-else>{{ formatFileSize(record.size) }}</span>
          </template>
        </a-table-column>
        <a-table-column title="修改时间" :width="160" data-index="modTime" />
        <a-table-column title="操作" :width="80">
          <template #default="{ record }">
            <a-button type="link" size="small" @click="downloadEntry(record)">下载</a-button>
          </template>
        </a-table-column>
      </a-table>
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
