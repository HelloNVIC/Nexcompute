<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import dayjs from 'dayjs'
import { Modal, message } from 'ant-design-vue'
import { instanceApi, type PhysicalInstance, type PowerShellResult } from '@/api/instance'
import { containerApi } from '@/api/container'
import { getSseClient } from '@/utils/sse'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const isAdmin = computed(() => auth.role === 'ADMIN')

const loading = ref(false)
const instances = ref<PhysicalInstance[]>([])
const selectedId = ref<number | undefined>()

// 选中的实例详情
const detail = reactive({
  recentHistory: [] as Array<{ recordedAt: string; cpuUsage?: number; gpuUsage?: number; memoryUsage?: number }>,
  aggregation: { myContainers: 0, otherUsers: 0, otherContainers: 0 },
  processes: '',
})

// 实时数据缓冲
const realtimeData = ref<Record<string, Array<{ time: string; cpu: number; gpu: number; mem: number }>>>({})

// PowerShell 对话框
const psVisible = ref(false)
const psCommand = ref('')
const psOutput = ref('')
const psLoading = ref(false)

// 编号修改对话框
const numVisible = ref(false)
const numForm = reactive({ id: 0, number: '' })

let unsubSse: (() => void) | null = null

onMounted(async () => {
  await load()
  subscribeSse()
})

onUnmounted(() => {
  unsubSse?.()
})

async function load(): Promise<void> {
  loading.value = true
  try {
    instances.value = await instanceApi.list()
    if (instances.value.length > 0 && !selectedId.value) {
      selectedId.value = instances.value[0].id
      await loadDetail()
    }
  } finally {
    loading.value = false
  }
}

async function loadDetail(): Promise<void> {
  if (!selectedId.value) return
  const id = selectedId.value
  try {
    const [recent, agg] = await Promise.all([
      monitoringApiRecent(id),
      containerApi.aggregation(id),
    ])
    detail.recentHistory = recent
    detail.aggregation = agg
    // 初始化实时缓冲
    realtimeData.value[id] = recent.slice(0, 60).reverse().map((d) => ({
      time: d.recordedAt,
      cpu: d.cpuUsage ?? 0,
      gpu: d.gpuUsage ?? 0,
      mem: d.memoryUsage ?? 0,
    }))
  } catch {
    // 忽略
  }
}

async function monitoringApiRecent(id: number) {
  const { http } = await import('@/utils/request')
  return http.get<Array<{ recordedAt: string; cpuUsage?: number; gpuUsage?: number; memoryUsage?: number }>>(
    `/monitoring/instances/${id}/recent`,
  )
}

function subscribeSse(): void {
  unsubSse?.()
  const sse = getSseClient()
  unsubSse = sse.on('monitoring', (data: unknown) => {
    const d = data as { instanceNumber: string; status: Record<string, number> }
    const inst = instances.value.find((i) => i.instanceNumber === d.instanceNumber)
    if (!inst) return
    const buf = realtimeData.value[inst.id] || (realtimeData.value[inst.id] = [])
    buf.push({
      time: new Date().toISOString(),
      cpu: d.status?.cpuUsage ?? 0,
      gpu: d.status?.gpuUsage ?? 0,
      mem: d.status?.memoryUsage ?? 0,
    })
    if (buf.length > 60) buf.shift()
    // 更新实例状态
    inst.status = 'ONLINE'
    inst.lastHeartbeat = new Date().toISOString()
  })
}

function parseStatus(status?: string): Record<string, number> | null {
  if (!status) return null
  try {
    return JSON.parse(status)
  } catch {
    return null
  }
}

// PowerShell 执行
function showPowerShell(instance: PhysicalInstance): void {
  selectedId.value = instance.id
  psCommand.value = ''
  psOutput.value = ''
  psVisible.value = true
}

async function runPowerShell(): Promise<void> {
  if (!selectedId.value || !psCommand.value) return
  psLoading.value = true
  psOutput.value = ''
  try {
    const result = await instanceApi.powershell(selectedId.value, psCommand.value)
    psOutput.value = result.output || result.error || '(无输出)'
  } catch {
    // 拦截器已提示
  } finally {
    psLoading.value = false
  }
}

// 编号修改
function showEditNumber(instance: PhysicalInstance): void {
  numForm.id = instance.id
  numForm.number = instance.instanceNumber
  numVisible.value = true
}

async function saveNumber(): Promise<void> {
  try {
    await instanceApi.updateNumber(numForm.id, numForm.number)
    message.success('编号已修改')
    numVisible.value = false
    load()
  } catch {
    // 拦截器已提示
  }
}

// 远程控制
function confirmRestart(instance: PhysicalInstance): void {
  Modal.confirm({
    title: `确认重启 ${instance.instanceNumber}？`,
    content: '机器将在 5 秒后重启',
    onOk: async () => {
      await instanceApi.restart(instance.id)
      message.success('重启指令已发送')
    },
  })
}

function confirmScreenOff(instance: PhysicalInstance): void {
  Modal.confirm({
    title: `确认息屏 ${instance.instanceNumber}？`,
    onOk: async () => {
      await instanceApi.screenOff(instance.id)
      message.success('息屏指令已发送')
    },
  })
}

// 图表配置
const chartOptions = {
  chart: { type: 'line', height: 180, animations: { enabled: true, easing: 'linear', dynamicAnimation: { speed: 1000 } } },
  xaxis: { type: 'datetime', labels: { show: false } },
  yaxis: { min: 0, max: 100 },
  stroke: { curve: 'smooth', width: 2 },
}

const realtimeBuf = computed(() => realtimeData.value[selectedId.value ?? 0] || [])

const cpuSeries = computed(() => [{ name: 'CPU %', data: realtimeBuf.value.map((d) => ({ x: d.time, y: d.cpu })) }])
const gpuSeries = computed(() => [{ name: 'GPU %', data: realtimeBuf.value.map((d) => ({ x: d.time, y: d.gpu })) }])
const memSeries = computed(() => [{ name: '内存 %', data: realtimeBuf.value.map((d) => ({ x: d.time, y: d.mem })) }])

const current = computed(() => instances.value.find((i) => i.id === selectedId.value))
const currentStatus = computed(() => parseStatus(current.value?.lastStatus))

// 磁盘分区（任务 2：从心跳状态快照解析）
interface DiskPartition {
  device: string
  mountpoint: string
  total: number
  used: number
  free: number
  usage: number
}
const diskPartitions = computed<DiskPartition[]>(() => {
  const status = currentStatus.value as Record<string, unknown> | null
  if (!status) return []
  const dp = status.diskPartitions
  return Array.isArray(dp) ? (dp as DiskPartition[]) : []
})

// 字节格式化
function formatBytes(bytes: number): string {
  if (!bytes) return '-'
  const gb = bytes / 1024 / 1024 / 1024
  if (gb >= 1) return gb.toFixed(1) + ' GB'
  const mb = bytes / 1024 / 1024
  return mb.toFixed(0) + ' MB'
}
</script>

<template>
  <div>
    <a-row :gutter="16">
      <!-- 左侧：实例列表 -->
      <a-col :span="8">
        <a-card title="物理实例" size="small" :loading="loading">
          <a-list :data-source="instances" size="small">
            <template #renderItem="{ item }">
              <a-list-item
                :class="{ 'selected-item': item.id === selectedId }"
                style="cursor: pointer; padding: 8px"
                @click="selectedId = item.id; loadDetail()"
              >
                <a-list-item-meta>
                  <template #title>
                    <span style="font-weight: 500">{{ item.instanceNumber }}</span>
                    <a-badge
                      :status="item.status === 'ONLINE' ? 'success' : 'error'"
                      :text="item.status === 'ONLINE' ? '在线' : '离线'"
                      style="margin-left: 8px"
                    />
                  </template>
                  <template #description>
                    {{ item.machineName || '未命名' }}
                    <br />
                    <span style="font-size: 12px; color: #999">{{ item.ipAddress || '-' }}</span>
                    <a-tag v-if="!item.storageRoot" color="orange" style="margin-left: 8px; font-size: 12px">未设存储池</a-tag>
                  </template>
                </a-list-item-meta>
              </a-list-item>
            </template>
          </a-list>
        </a-card>
      </a-col>

      <!-- 右侧：实例详情 -->
      <a-col :span="16">
        <div v-if="!current">
          <a-empty description="选择一台物理实例查看详情" />
        </div>
        <div v-else>
          <!-- 基本信息 -->
          <a-card size="small" style="margin-bottom: 16px">
            <a-descriptions :column="2" bordered size="small">
              <a-descriptions-item label="编号">{{ current.instanceNumber }}</a-descriptions-item>
              <a-descriptions-item label="状态">
                <a-badge :status="current.status === 'ONLINE' ? 'success' : 'error'" :text="current.status === 'ONLINE' ? '在线' : '离线'" />
              </a-descriptions-item>
              <a-descriptions-item label="机器名">{{ current.machineName || '-' }}</a-descriptions-item>
              <a-descriptions-item label="IP">{{ current.ipAddress || '-' }}</a-descriptions-item>
              <a-descriptions-item label="连接模式">
                <a-tag>{{ current.connectMode === 'direct' ? '直连' : '穿透' }}</a-tag>
              </a-descriptions-item>
              <a-descriptions-item label="最后心跳">
                {{ current.lastHeartbeat ? dayjs(current.lastHeartbeat).format('YYYY-MM-DD HH:mm:ss') : '-' }}
              </a-descriptions-item>
              <a-descriptions-item label="OS">{{ current.osInfo || '-' }}</a-descriptions-item>
              <a-descriptions-item label="GPU">{{ current.gpuInfo || '-' }}</a-descriptions-item>
            </a-descriptions>
          </a-card>

          <!-- 未设存储池根目录警告 -->
          <a-alert
            v-if="!current.storageRoot"
            type="warning"
            show-icon
            message="该实例未设置存储池根目录"
            description="受控端尚未配置存储池根目录，无法在此实例上创建存储池。请在受控端 GUI 设置根目录后等待心跳同步。"
            style="margin-bottom: 16px"
          />

          <!-- 实时状态图表 -->
          <a-card title="实时状态" size="small" style="margin-bottom: 16px" v-if="current.status === 'ONLINE'">
            <a-row :gutter="16">
              <a-col :span="8">
                <div style="font-size: 12px; color: #666; margin-bottom: 4px">CPU {{ currentStatus?.cpuUsage?.toFixed(1) ?? 0 }}%</div>
                <ApexChart type="line" height="180" :options="chartOptions" :series="cpuSeries" />
              </a-col>
              <a-col :span="8">
                <div style="font-size: 12px; color: #666; margin-bottom: 4px">GPU {{ currentStatus?.gpuUsage?.toFixed(1) ?? 0 }}%</div>
                <ApexChart type="line" height="180" :options="chartOptions" :series="gpuSeries" />
              </a-col>
              <a-col :span="8">
                <div style="font-size: 12px; color: #666; margin-bottom: 4px">内存 {{ currentStatus?.memoryUsage?.toFixed(1) ?? 0 }}%</div>
                <ApexChart type="line" height="180" :options="chartOptions" :series="memSeries" />
              </a-col>
            </a-row>
          </a-card>

          <!-- 磁盘分区存储空间（任务 2） -->
          <a-card title="磁盘分区" size="small" style="margin-bottom: 16px" v-if="current.status === 'ONLINE' && diskPartitions.length">
            <a-table :data-source="diskPartitions" size="small" :pagination="false" row-key="device">
              <a-table-column title="分区/盘符" data-index="device" :width="120" />
              <a-table-column title="挂载点" data-index="mountpoint" :width="150" />
              <a-table-column title="总容量">
                <template #default="{ record }">{{ formatBytes(record.total) }}</template>
              </a-table-column>
              <a-table-column title="已用">
                <template #default="{ record }">{{ formatBytes(record.used) }}</template>
              </a-table-column>
              <a-table-column title="可用">
                <template #default="{ record }">{{ formatBytes(record.free) }}</template>
              </a-table-column>
              <a-table-column title="使用率" :width="180">
                <template #default="{ record }">
                  <a-progress
                    :percent="Math.round(record.usage)"
                    :stroke-color="record.usage > 90 ? '#ff4d4f' : record.usage > 70 ? '#faad14' : '#52c41a'"
                    size="small"
                  />
                </template>
              </a-table-column>
            </a-table>
          </a-card>

          <!-- 聚合统计 -->
          <a-card title="资源统计" size="small" style="margin-bottom: 16px" v-if="current.status === 'ONLINE'">
            <a-statistic title="另有其他用户容器" :value="detail.aggregation.otherContainers" :value-style="{ color: '#1677ff' }" />
          </a-card>

          <!-- 管理操作（仅管理员） -->
          <a-card title="管理操作" size="small" v-if="isAdmin">
            <a-space wrap>
              <a-button size="small" @click="showEditNumber(current)">改编号</a-button>
              <a-button size="small" @click="confirmRestart(current)">重启</a-button>
              <a-button size="small" @click="confirmScreenOff(current)">息屏</a-button>
              <a-button size="small" @click="showPowerShell(current)">PowerShell</a-button>
            </a-space>
          </a-card>
        </div>
      </a-col>
    </a-row>

    <!-- PowerShell 对话框 -->
    <a-modal v-model:open="psVisible" :title="`PowerShell - ${current?.instanceNumber}`" width="700px" :footer="null">
      <div style="display: flex; gap: 8px; width: 100%; margin-bottom: 12px">
        <a-input v-model:value="psCommand" placeholder="输入 PowerShell 命令" @keyup.enter="runPowerShell" />
        <a-button type="primary" :loading="psLoading" @click="runPowerShell">执行</a-button>
      </div>
      <a-typography-paragraph type="secondary">输出：</a-typography-paragraph>
      <pre style="background: #1e1e1e; color: #d4d4d4; padding: 12px; border-radius: 4px; max-height: 300px; overflow: auto; white-space: pre-wrap">{{ psOutput || '(尚未执行)' }}</pre>
    </a-modal>

    <!-- 编号修改对话框 -->
    <a-modal v-model:open="numVisible" title="修改物理机编号" @ok="saveNumber">
      <a-form layout="vertical">
        <a-form-item label="新编号（全系统唯一）">
          <a-input v-model:value="numForm.number" />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>

<style scoped>
.selected-item {
  background: #e6f4ff;
  border-left: 3px solid #1677ff;
}
</style>
