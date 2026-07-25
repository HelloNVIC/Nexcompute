<script setup lang="ts">
import { onMounted, onUnmounted, ref, computed } from 'vue'
import dayjs from 'dayjs'
import { instanceApi, type PhysicalInstance } from '@/api/instance'
import { monitoringApi, type MonitoringHistory } from '@/api/monitoring'
import { containerApi } from '@/api/container'
import { getSseClient } from '@/utils/sse'

const loading = ref(false)
const instances = ref<PhysicalInstance[]>([])
const selectedId = ref<number | undefined>()
const history = ref<MonitoringHistory[]>([])
const processes = ref<string>('')
const aggregation = ref<{
  myContainers: number
  otherUsers: number
  otherContainers: number
  users?: Array<{ userId: number; realName: string; studentId?: string; containerCount: number; containers: Array<{ name: string; status: string }> }>
}>({ myContainers: 0, otherUsers: 0, otherContainers: 0 })

// 实时数据缓冲（最近 60 个点）
const realtimeData = ref<MonitoringHistory[]>([])

let unsubSse: (() => void) | null = null

onMounted(async () => {
  loading.value = true
  try {
    instances.value = await instanceApi.list()
    if (instances.value.length > 0) {
      selectedId.value = instances.value[0].id
      await loadInstanceData()
    }
  } finally {
    loading.value = false
  }
})

onUnmounted(() => {
  unsubSse?.()
})

async function loadInstanceData(): Promise<void> {
  if (!selectedId.value) return
  const [recent, agg] = await Promise.all([
    monitoringApi.recent(selectedId.value),
    containerApi.aggregation(selectedId.value),
  ])
  history.value = recent
  realtimeData.value = recent.slice(0, 60).reverse()
  aggregation.value = agg
  subscribeSse()
}

function subscribeSse(): void {
  unsubSse?.()
  const sse = getSseClient()
  unsubSse = sse.on('monitoring', (data: unknown) => {
    const d = data as { instanceNumber: string; status: Record<string, number> }
    const inst = instances.value.find((i) => i.instanceNumber === d.instanceNumber)
    if (!inst || inst.id !== selectedId.value) return
    realtimeData.value.push({
      id: Date.now(),
      instanceId: inst.id,
      instanceNumber: d.instanceNumber,
      cpuUsage: d.status?.cpuUsage,
      gpuUsage: d.status?.gpuUsage,
      memoryUsage: d.status?.memoryUsage,
      recordedAt: new Date().toISOString(),
    })
    if (realtimeData.value.length > 60) {
      realtimeData.value.shift()
    }
  })
}

async function loadProcesses(): Promise<void> {
  if (!selectedId.value) return
  try {
    processes.value = await monitoringApi.processes(selectedId.value)
  } catch {
    // 拦截器已提示
  }
}

async function loadHistory(): Promise<void> {
  if (!selectedId.value) return
  const start = dayjs().subtract(30, 'day').toISOString()
  history.value = await monitoringApi.history(selectedId.value, start)
}

// 图表配置
const chartOptions = computed(() => ({
  chart: { type: 'line', height: 200, animations: { enabled: true, easing: 'linear', dynamicAnimation: { speed: 1000 } } },
  xaxis: { type: 'datetime', labels: { show: false } },
  yaxis: { min: 0, max: 100 },
  stroke: { curve: 'smooth', width: 2 },
  legend: { position: 'top' },
}))

const cpuSeries = computed(() => [{
  name: 'CPU %',
  data: realtimeData.value.map((d) => ({ x: d.recordedAt, y: d.cpuUsage ?? 0 })),
}])

const gpuSeries = computed(() => [{
  name: 'GPU %',
  data: realtimeData.value.map((d) => ({ x: d.recordedAt, y: d.gpuUsage ?? 0 })),
}])

const memorySeries = computed(() => [{
  name: '内存 %',
  data: realtimeData.value.map((d) => ({ x: d.recordedAt, y: d.memoryUsage ?? 0 })),
}])

const historyChartOptions = {
  chart: { type: 'line', height: 300 },
  xaxis: { type: 'datetime' },
  stroke: { curve: 'smooth', width: 2 },
  legend: { position: 'top' },
}

const historySeries = computed(() => [
  { name: 'CPU %', data: history.value.map((d) => ({ x: d.recordedAt, y: d.cpuUsage ?? 0 })).reverse() },
  { name: 'GPU %', data: history.value.map((d) => ({ x: d.recordedAt, y: d.gpuUsage ?? 0 })).reverse() },
  { name: '内存 %', data: history.value.map((d) => ({ x: d.recordedAt, y: d.memoryUsage ?? 0 })).reverse() },
])

// GPU 显存历史趋势（MiB，platform-improvements 任务 4.5）
const gpuMemHistorySeries = computed(() => [
  { name: '显存已用(MiB)', data: history.value.map((d) => ({ x: d.recordedAt, y: d.gpuMemoryUsed ?? 0 })).reverse() },
  { name: '显存总量(MiB)', data: history.value.map((d) => ({ x: d.recordedAt, y: d.gpuMemoryTotal ?? 0 })).reverse() },
])

// 当前选中实例的结构化 GPU 信息（型号/显存，任务 4.4，解析 lastStatus.gpuInfo）
interface StructuredGpu {
  name?: string
  memoryTotal?: number
  memoryUsed?: number
  utilization?: number
  temperature?: number
}
const selectedGpuInfo = computed<StructuredGpu | null>(() => {
  const inst = instances.value.find((i) => i.id === selectedId.value)
  if (!inst?.lastStatus) return null
  try {
    const st = JSON.parse(inst.lastStatus)
    return st?.gpuInfo ?? null
  } catch {
    return null
  }
})
</script>

<template>
  <div>
    <a-typography-title :level="3">物理实例状态</a-typography-title>

    <a-space style="margin-bottom: 16px">
      <a-select v-model:value="selectedId" style="width: 250px" @change="loadInstanceData">
        <a-select-option v-for="i in instances" :key="i.id" :value="i.id">
          {{ i.instanceNumber }} - {{ i.machineName }}
        </a-select-option>
      </a-select>
      <a-button @click="loadHistory">查看 30 日历史</a-button>
      <a-button @click="loadProcesses">查看进程</a-button>
    </a-space>

    <div v-if="selectedId">
      <!-- 聚合统计 -->
      <a-alert type="info" style="margin-bottom: 16px"
        :message="`另有 ${aggregation.otherUsers} 用户 ${aggregation.otherContainers} 容器`" />

      <!-- 平台-refinements #4：管理员可见其他用户与容器 -->
      <a-card v-if="aggregation.users && aggregation.users.length" title="实例上各用户与容器" size="small" style="margin-bottom: 16px">
        <a-list :data-source="aggregation.users" size="small">
          <template #renderItem="{ item }">
            <a-list-item>
              <a-list-item-meta>
                <template #title>
                  {{ item.realName }}
                  <span v-if="item.studentId" style="color: #999; font-size: 12px">（{{ item.studentId }}）</span>
                  <a-tag color="blue" style="margin-left: 8px">{{ item.containerCount }} 容器</a-tag>
                </template>
                <template #description>
                  <a-tag v-for="c in item.containers" :key="c.name" :color="c.status === 'RUNNING' ? 'green' : 'default'" style="margin: 2px">
                    {{ c.name }} · {{ c.status }}
                  </a-tag>
                </template>
              </a-list-item-meta>
            </a-list-item>
          </template>
        </a-list>
      </a-card>

      <!-- 结构化 GPU 信息（型号/显存，任务 4.4） -->
      <a-card v-if="selectedGpuInfo" title="GPU 信息" size="small" style="margin-bottom: 16px">
        <a-descriptions :column="4" size="small">
          <a-descriptions-item label="型号">{{ selectedGpuInfo.name || '-' }}</a-descriptions-item>
          <a-descriptions-item label="总显存">{{ selectedGpuInfo.memoryTotal ? selectedGpuInfo.memoryTotal + ' MiB' : '-' }}</a-descriptions-item>
          <a-descriptions-item label="已用显存">{{ selectedGpuInfo.memoryUsed != null ? selectedGpuInfo.memoryUsed + ' MiB' : '-' }}</a-descriptions-item>
          <a-descriptions-item label="利用率/温度">
            {{ selectedGpuInfo.utilization != null ? selectedGpuInfo.utilization.toFixed(1) + '%' : '-' }}
            / {{ selectedGpuInfo.temperature != null ? selectedGpuInfo.temperature.toFixed(0) + '°C' : '-' }}
          </a-descriptions-item>
        </a-descriptions>
      </a-card>
      <a-card v-else title="GPU 信息" size="small" style="margin-bottom: 16px">
        <a-typography-text type="secondary">无 GPU 或未上报结构化 GPU 信息</a-typography-text>
      </a-card>

      <!-- 实时图表 -->
      <a-row :gutter="16" style="margin-bottom: 16px">
        <a-col :span="8">
          <a-card title="CPU 占用" size="small">
            <ApexChart type="line" height="200" :options="chartOptions" :series="cpuSeries" />
          </a-card>
        </a-col>
        <a-col :span="8">
          <a-card title="GPU 占用" size="small">
            <ApexChart type="line" height="200" :options="chartOptions" :series="gpuSeries" />
          </a-card>
        </a-col>
        <a-col :span="8">
          <a-card title="内存占用" size="small">
            <ApexChart type="line" height="200" :options="chartOptions" :series="memorySeries" />
          </a-card>
        </a-col>
      </a-row>

      <!-- 历史趋势 -->
      <a-card v-if="history.length" title="历史趋势（近 30 日）" style="margin-bottom: 16px">
        <ApexChart type="line" height="300" :options="historyChartOptions" :series="historySeries" />
      </a-card>

      <!-- GPU 显存历史趋势（任务 4.5） -->
      <a-card v-if="history.length && history.some((d) => d.gpuMemoryTotal || d.gpuMemoryUsed)" title="GPU 显存趋势（MiB）" style="margin-bottom: 16px">
        <ApexChart type="line" height="220" :options="historyChartOptions" :series="gpuMemHistorySeries" />
      </a-card>

      <!-- 进程列表 -->
      <a-card v-if="processes" title="进程列表">
        <pre style="max-height: 400px; overflow: auto; background: #f5f5f5; padding: 12px; border-radius: 4px">{{ processes }}</pre>
      </a-card>
    </div>
  </div>
</template>
