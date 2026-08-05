<script setup lang="ts">
import { onMounted, reactive, ref, computed } from 'vue'
import { Modal, message } from 'ant-design-vue'
import dayjs from 'dayjs'
import { imageApi, type ImageMetadata } from '@/api/image'
import { groupApi, type ResearchGroup } from '@/api/group'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
// platform-refinements：管理员视角为"权限"管理，普通用户为"共享"自己的镜像
const shareLabel = computed(() => (auth.role === 'ADMIN' ? '权限' : '共享'))

const loading = ref(false)
const images = ref<ImageMetadata[]>([])

// 上传 tar 弹窗
const uploadVisible = ref(false)
const submitting = ref(false)
const parsing = ref(false)

const tarForm = reactive({
  name: '',
  tag: 'latest',
  file: null as File | null,
  appPorts: [] as number[],
  usageInstructions: '',
  mountPoint: '',
})
const fileList = ref<{ uid: string; name: string; status: string; originFileObj: File }[]>([])

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

function showUpload(): void {
  uploadVisible.value = true
  resetTarForm()
}

function resetTarForm(): void {
  tarForm.name = ''
  tarForm.tag = 'latest'
  tarForm.file = null
  tarForm.appPorts = []
  tarForm.usageInstructions = ''
  tarForm.mountPoint = ''
  fileList.value = []
}

// 选定 tar 即时解析回填（platform-refinements 4.3）：调用 parse-tar 回填 name/tag/appPorts
async function handleBeforeUpload(file: File): Promise<boolean> {
  tarForm.file = file
  fileList.value = [{ uid: String(Date.now()), name: file.name, status: 'done', originFileObj: file }]
  parsing.value = true
  try {
    const parsed = await imageApi.parseTar(file)
    if (parsed.name) tarForm.name = parsed.name
    if (parsed.tag) tarForm.tag = parsed.tag
    if (parsed.appPorts?.length) tarForm.appPorts = parsed.appPorts
    message.success('已解析 tar 并回填')
  } catch {
    // 拦截器已提示；用户可手动填写
  } finally {
    parsing.value = false
  }
  return false
}

function handleRemoveFile(): boolean {
  tarForm.file = null
  fileList.value = []
  return true
}

function appPortChange(ports: (string | number)[]): void {
  tarForm.appPorts = dedupePorts(ports)
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

async function handleSubmit(): Promise<void> {
  if (!tarForm.file) {
    message.warning('请选择 tar 文件')
    return
  }
  if (!tarForm.name) {
    message.warning('未能从 tar 解析出镜像名，请确认 tar 由 docker save 生成且含 RepoTags')
    return
  }
  submitting.value = true
  try {
    const result = await imageApi.uploadTar(
      tarForm.file,
      tarForm.name,
      tarForm.tag,
      tarForm.appPorts.length ? tarForm.appPorts : undefined,
      tarForm.usageInstructions || undefined,
      tarForm.mountPoint || undefined,
    )
    message.success(`tar 镜像上传成功：${result.name}:${result.tag}`)
    uploadVisible.value = false
    load()
  } catch {
    // 拦截器已提示
  } finally {
    submitting.value = false
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

function confirmDelete(image: ImageMetadata): void {
  Modal.confirm({
    title: `确认删除镜像 ${image.name}:${image.tag}？`,
    content: '删除后不可恢复',
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
      <a-button type="primary" @click="showUpload">上传 tar</a-button>
    </div>

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
      <a-table-column title="大小" :width="100">
        <template #default="{ record }">{{ formatSize(record.sizeBytes) }}</template>
      </a-table-column>
      <a-table-column title="来源" :width="110">
        <template #default="{ record }">
          <a-tag v-if="record.sourceContainer === 'tar-upload'" color="cyan">tar上传</a-tag>
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
            {{ record.status === 'READY' ? '就绪' : record.status === 'UPLOADING' ? '上传中' : record.status === 'FAILED' ? '失败' : record.status }}
          </a-tag>
        </template>
      </a-table-column>
      <a-table-column title="创建时间" :width="160">
        <template #default="{ record }">{{ dayjs(record.createdAt).format('YYYY-MM-DD HH:mm') }}</template>
      </a-table-column>
      <a-table-column title="操作" :width="200">
        <template #default="{ record }">
          <a-button type="link" size="small" @click="openEdit(record)">编辑</a-button>
          <a-button type="link" size="small" @click="downloadImage(record)">下载</a-button>
          <a-button type="link" size="small" @click="openShare(record)">{{ shareLabel }}</a-button>
          <a-button type="link" size="small" danger @click="confirmDelete(record)">删除</a-button>
        </template>
      </a-table-column>
    </a-table>

    <!-- 上传 tar 对话框 -->
    <a-modal
      v-model:open="uploadVisible"
      title="上传 tar 镜像"
      width="700px"
      :confirm-loading="submitting"
      @ok="handleSubmit"
    >
      <a-form layout="vertical">
        <a-form-item label="镜像名（由 tar RepoTags 解析，不可修改）">
          <a-input v-model:value="tarForm.name" disabled placeholder="选定 tar 后自动解析" />
        </a-form-item>
        <a-form-item label="标签（由 tar RepoTags 解析，不可修改）">
          <a-input v-model:value="tarForm.tag" disabled placeholder="选定 tar 后自动解析" />
        </a-form-item>
        <a-form-item label="应用端口（多个，可留空；选定 tar 后自动解析 ExposedPorts 预填）">
          <a-select
            :value="tarForm.appPorts"
            mode="tags"
            placeholder="如 8888, 6006（留空则自动解析）"
            @change="appPortChange"
          />
        </a-form-item>
        <a-form-item label="使用说明">
          <a-textarea
            v-model:value="tarForm.usageInstructions"
            :rows="2"
            placeholder="镜像用途、启动方式等说明（可选）"
          />
        </a-form-item>
        <a-form-item label="容器内挂载">
          <a-input
            v-model:value="tarForm.mountPoint"
            placeholder="如 /workspace（存储池映射到容器内的路径，创建容器时自动预填）"
          />
        </a-form-item>
        <a-form-item label="拖拽上传 tar 文件" required>
          <a-upload-dragger
            v-model:file-list="fileList"
            :before-upload="handleBeforeUpload"
            :max-count="1"
            accept=".tar"
            @remove="handleRemoveFile"
          >
            <p style="font-size: 16px"><strong>点击或拖拽 tar 文件到此区域上传</strong></p>
            <p style="color: #999; font-size: 12px">
              {{ parsing ? '正在解析 tar…' : '选定后自动解析并回填名称/标签/端口，上传后存储于管理端' }}
            </p>
          </a-upload-dragger>
        </a-form-item>
      </a-form>
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
          message="所有用户均可在镜像列表看到并使用该镜像创建容器（不自动同步至受控端，按需 docker load）。"
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
  </div>
</template>

<style scoped>
.ellipsis {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
