<script setup lang="ts">
// 首页仪表盘（platform-audit-logging-ux 丰富统计与动态图表）。
// 按角色聚合：物理实例/容器/存储池/镜像/用户/课题组等统计 + 容器状态分布（环形图）+
// 实例在线率（柱状）+ 实时监控 CPU/GPU/内存（折线）+ 系统信息 + 实时时钟。
import { onMounted, onUnmounted, ref, computed } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { announcementApi, type Announcement } from '@/api/announcement'
import { instanceApi, type PhysicalInstance } from '@/api/instance'
import { containerApi, type Container } from '@/api/container'
import { imageApi } from '@/api/image'
import { storagePoolApi, type StoragePool } from '@/api/storagePool'
import { groupApi, type UserInfoDto } from '@/api/group'
import { userApi } from '@/api/user'
import { allocationApi } from '@/api/allocation'
import { monitoringApi, type MonitoringHistory } from '@/api/monitoring'
import { systemInfoApi, type SystemInfo } from '@/api/systemInfo'

const auth = useAuthStore()
const announcements = ref<Announcement[]>([])
const instances = ref<PhysicalInstance[]>([])
const containers = ref<Container[]>([])
const images = ref<unknown[]>([])
const pools = ref<StoragePool[]>([])
const groupsCount = ref(0)
const usersCount = ref(0)
const students = ref<UserInfoDto[]>([])
const groupAllocCount = ref(0)
const systemInfo = ref<SystemInfo | null>(null)
const monitoring = ref<MonitoringHistory[]>([])

const roleLabel: Record<string, string> = { ADMIN: '管理员', MENTOR: '导师', STUDENT: '学生' }

const onlineInstances = computed(() => instances.value.filter((i) => i.status === 'ONLINE').length)
const offlineInstances = computed(() => instances.value.length - onlineInstances.value)
const runningContainers = computed(() => containers.value.filter((c) => c.status === 'RUNNING').length)
const stoppedContainers = computed(() => containers.value.filter((c) => c.status === 'STOPPED' || c.status === 'EXITED').length)
const otherContainers = computed(() => containers.value.length - runningContainers.value - stoppedContainers.value)

// 实时时钟
const now = ref(new Date())
let clockTimer: ReturnType<typeof setInterval> | null = null

onMounted(async () => {
  clockTimer = setInterval(() => { now.value = new Date() }, 1000)

  try {
    const list = await announcementApi.list().catch(() => [])
    announcements.value = (list || []).filter((a) => a.status === 'PUBLISHED').slice(0, 6)
  } catch { /* 静默 */ }
  try { instances.value = await instanceApi.list() } catch { /* */ }
  try { containers.value = await containerApi.list() } catch { /* */ }
  try { pools.value = await storagePoolApi.list() } catch { /* */ }
  try { systemInfo.value = await systemInfoApi.get().catch(() => null) } catch { /* */ }

  if (auth.role === 'ADMIN') {
    try { images.value = await imageApi.list() } catch { /* */ }
    try { groupsCount.value = (await groupApi.list()).length } catch { /* */ }
    try { usersCount.value = (await userApi.list({ page: 0, size: 1 })).totalElements } catch { /* */ }
    // 取首个在线实例的最近监控做折线图
    const online = instances.value.find((i) => i.status === 'ONLINE')
    if (online) {
      try { monitoring.value = await monitoringApi.recent(online.id) } catch { /* */ }
    }
  } else if (auth.role === 'MENTOR') {
    const g = await groupApi.my().catch(() => null)
    if (g) {
      try { students.value = await groupApi.students(g.id) } catch { /* */ }
    }
    try { groupAllocCount.value = (await allocationApi.groupAllocations()).length } catch { /* */ }
  }
})

onUnmounted(() => {
  if (clockTimer) clearInterval(clockTimer)
})

function formatTime(t: string): string {
  if (!t) return ''
  return new Date(t).toLocaleString('zh-CN', { hour12: false })
}

interface Stat { label: string; value: number | string; sub?: string; color: string }
const stats = computed<Stat[]>(() => {
  const c: Stat[] = []
  if (auth.role === 'ADMIN') {
    c.push({ label: '物理实例', value: instances.value.length, sub: `在线 ${onlineInstances.value}`, color: '#1677ff' })
    c.push({ label: '在线实例', value: onlineInstances.value, sub: `离线 ${offlineInstances.value}`, color: '#52c41a' })
    c.push({ label: '容器总数', value: containers.value.length, color: '#722ed1' })
    c.push({ label: '运行容器', value: runningContainers.value, sub: `停止 ${stoppedContainers.value}`, color: '#13c2c2' })
    c.push({ label: '镜像', value: images.value.length, color: '#eb2f96' })
    c.push({ label: '存储池', value: pools.value.length, color: '#fa8c16' })
    c.push({ label: '课题组', value: groupsCount.value, color: '#2f54eb' })
    c.push({ label: '用户', value: usersCount.value, color: '#08979c' })
    c.push({ label: '在线率', value: instances.value.length ? Math.round(onlineInstances.value * 100 / instances.value.length) + '%' : '0%', color: '#389e0d' })
    c.push({ label: '运行率', value: containers.value.length ? Math.round(runningContainers.value * 100 / containers.value.length) + '%' : '0%', color: '#c41d7f' })
  } else if (auth.role === 'MENTOR') {
    c.push({ label: '课题组成员', value: students.value.length, color: '#1677ff' })
    c.push({ label: '已分配实例', value: groupAllocCount.value, color: '#52c41a' })
    c.push({ label: '组内容器', value: containers.value.length, sub: `运行 ${runningContainers.value}`, color: '#722ed1' })
    c.push({ label: '运行容器', value: runningContainers.value, color: '#13c2c2' })
    c.push({ label: '可访问存储池', value: pools.value.length, color: '#eb2f96' })
    c.push({ label: '可用实例', value: instances.value.length, sub: `在线 ${onlineInstances.value}`, color: '#fa8c16' })
  } else {
    c.push({ label: '我的容器', value: containers.value.length, sub: `运行 ${runningContainers.value}`, color: '#1677ff' })
    c.push({ label: '运行容器', value: runningContainers.value, color: '#52c41a' })
    c.push({ label: '我的存储池', value: pools.value.length, color: '#722ed1' })
    c.push({ label: '可用实例', value: instances.value.length, sub: `在线 ${onlineInstances.value}`, color: '#13c2c2' })
  }
  return c
})

// 容器状态分布（环形图）
const containerStatusSeries = computed(() => [runningContainers.value, stoppedContainers.value, otherContainers.value])
const containerStatusOptions = {
  chart: { type: 'donut' as const, height: 260 },
  labels: ['运行中', '已停止', '其他'],
  colors: ['#1677ff', '#fa8c16', '#bfbfbf'],
  legend: { position: 'bottom' as const },
  dataLabels: { enabled: true },
  plotOptions: { pie: { donut: { size: '60%' } } },
}

// 实例在线/离线（柱状图）
const instanceBarSeries = computed(() => [{ name: '实例', data: [onlineInstances.value, offlineInstances.value] }])
const instanceBarOptions = {
  chart: { type: 'bar' as const, height: 260 },
  plotOptions: { bar: { distributed: true, columnWidth: '50%' } },
  colors: ['#1677ff', '#bfbfbf'],
  xaxis: { categories: ['在线', '离线'] },
  legend: { show: false },
  dataLabels: { enabled: true },
}

// 各实例容器数（横向柱状图）
const instanceContainerSeries = computed(() => {
  const map = new Map<string, number>()
  containers.value.forEach((c) => {
    const key = (c as { instanceNumber?: string }).instanceNumber || '未知'
    map.set(key, (map.get(key) ?? 0) + 1)
  })
  const entries = Array.from(map.entries()).slice(0, 10)
  return [{ name: '容器数', data: entries.map((e) => e[1]) }]
})
const instanceContainerOptions = computed(() => ({
  chart: { type: 'bar' as const, height: 260 },
  plotOptions: { bar: { horizontal: true, columnWidth: '60%' } },
  colors: ['#1677ff'],
  xaxis: { categories: Array.from(new Set(containers.value.map((c) => (c as { instanceNumber?: string }).instanceNumber || '未知'))).slice(0, 10) },
  dataLabels: { enabled: true },
}))

// 监控折线（CPU/GPU/内存）
const monitorSeries = computed(() => {
  const rev = [...monitoring.value].reverse()
  return [
    { name: 'CPU %', data: rev.map((m) => m.cpuUsage ?? 0) },
    { name: 'GPU %', data: rev.map((m) => m.gpuUsage ?? 0) },
    { name: '内存 %', data: rev.map((m) => (m.memoryTotal ? Math.round((m.memoryUsed ?? 0) * 100 / m.memoryTotal) : 0)) },
  ]
})
const monitorOptions = computed(() => ({
  chart: { type: 'line' as const, height: 260, animations: { enabled: true } },
  stroke: { curve: 'smooth' as const, width: 2 },
  xaxis: { categories: [...monitoring.value].reverse().map((m) => new Date(m.recordedAt).toLocaleTimeString('zh-CN', { hour12: false })) },
  yaxis: { min: 0, max: 100 },
  tooltip: { shared: true },
  legend: { position: 'top' as const },
}))

// 受控端信息（取首个在线实例的详情）
const agentInfo = computed(() => {
  const online = instances.value.filter((i) => i.status === 'ONLINE')
  return online.map((i) => ({
    instanceNumber: i.instanceNumber,
    machineName: i.machineName || '未命名',
    ipAddress: i.ipAddress || '-',
    agentVersion: i.agentVersion || '-',
    osInfo: i.osInfo || '-',
    gpuInfo: i.gpuInfo || '-',
    storageRoot: i.storageRoot || '-',
    connectMode: i.connectMode || '-',
    lastHeartbeat: i.lastHeartbeat || '',
    status: i.status,
  }))
})
</script>

<template>
  <div>
    <!-- 顶部：用户信息 + 实时时钟 -->
    <a-card>
      <div style="display: flex; align-items: center; gap: 16px">
        <a-avatar size="large" style="background-color: #1677ff">
          {{ (auth.user?.realName ?? auth.user?.username ?? '?').charAt(0) }}
        </a-avatar>
        <div style="flex: 1">
          <a-typography-title :level="4" style="margin: 0">
            欢迎，{{ auth.user?.realName ?? auth.user?.username }}
          </a-typography-title>
          <a-space size="small" style="margin-top: 4px">
            <a-tag color="blue">{{ roleLabel[auth.user?.role ?? ''] ?? auth.user?.role }}</a-tag>
            <span v-if="auth.user?.studentId" style="color: #999">工号/学号：{{ auth.user.studentId }}</span>
            <span v-if="auth.user?.email" style="color: #999">{{ auth.user.email }}</span>
          </a-space>
        </div>
        <div style="text-align: right">
          <div style="font-size: 22px; font-weight: 600; color: #1677ff; font-variant-numeric: tabular-nums">
            {{ now.toLocaleTimeString('zh-CN', { hour12: false }) }}
          </div>
          <a-typography-text type="secondary" style="font-size: 12px">
            {{ now.toLocaleDateString('zh-CN', { weekday: 'long', year: 'numeric', month: 'long', day: 'numeric' }) }}
          </a-typography-text>
        </div>
      </div>
    </a-card>

    <!-- 统计卡片（按角色） -->
    <a-row :gutter="16" style="margin-top: 28px">
      <a-col v-for="s in stats" :key="s.label" :xs="12" :sm="8" :md="6" :lg="4" :xl="3">
        <a-card size="small" :bordered="true" class="stat-card">
          <a-statistic :title="s.label" :value="s.value" :value-style="{ color: s.color, fontSize: '22px' }">
            <template v-if="s.sub" #suffix>
              <span style="font-size: 12px; color: #999">{{ s.sub }}</span>
            </template>
          </a-statistic>
        </a-card>
      </a-col>
    </a-row>

    <!-- 图表区：容器状态分布 + 实例在线率 -->
    <a-row :gutter="16" style="margin-top: 28px">
      <a-col :span="12">
        <a-card size="small" :body-style="{ padding: '12px' }">
          <template #title><span class="card-title">容器状态分布</span></template>
          <a-empty v-if="containers.length === 0" description="暂无容器" />
          <ApexChart v-else type="donut" :series="containerStatusSeries" :options="containerStatusOptions" height="260" />
        </a-card>
      </a-col>
      <a-col :span="12">
        <a-card size="small" :body-style="{ padding: '12px' }">
          <template #title><span class="card-title">物理实例在线率</span></template>
          <a-empty v-if="instances.length === 0" description="暂无实例" />
          <ApexChart v-else type="bar" :series="instanceBarSeries" :options="instanceBarOptions" height="260" />
        </a-card>
      </a-col>
    </a-row>

    <!-- 图表区：各实例容器数 + 实时监控 -->
    <a-row :gutter="16" style="margin-top: 28px">
      <a-col :span="12">
        <a-card size="small" :body-style="{ padding: '12px' }">
          <template #title><span class="card-title">各实例容器数</span></template>
          <a-empty v-if="containers.length === 0" description="暂无容器" />
          <ApexChart v-else type="bar" :series="instanceContainerSeries" :options="instanceContainerOptions" height="260" />
        </a-card>
      </a-col>
      <a-col :span="12">
        <a-card size="small" :body-style="{ padding: '12px' }">
          <template #title><span class="card-title">实时监控（首个在线实例）</span></template>
          <a-empty v-if="monitoring.length === 0" description="暂无监控数据" />
          <ApexChart v-else type="line" :series="monitorSeries" :options="monitorOptions" height="260" />
        </a-card>
      </a-col>
    </a-row>

    <!-- 系统信息 + 受控端信息 -->
    <a-row :gutter="16" style="margin-top: 28px">
      <a-col :span="8">
        <a-card size="small">
          <template #title><span class="card-title">系统信息</span></template>
          <a-descriptions :column="1" size="small">
            <a-descriptions-item label="平台版本">合算 Nexcompute 管理端 v0.1.0</a-descriptions-item>
            <a-descriptions-item v-if="systemInfo?.owner" label="所有者">{{ systemInfo.owner }}</a-descriptions-item>
            <a-descriptions-item v-if="systemInfo?.ownerPhone" label="所有者电话">{{ systemInfo.ownerPhone }}</a-descriptions-item>
            <a-descriptions-item v-if="systemInfo?.maintainer" label="维护人">{{ systemInfo.maintainer }}</a-descriptions-item>
            <a-descriptions-item v-if="systemInfo?.maintainerPhone" label="维护电话">{{ systemInfo.maintainerPhone }}</a-descriptions-item>
            <a-descriptions-item label="在线实例">{{ onlineInstances }} / {{ instances.length }}</a-descriptions-item>
            <a-descriptions-item label="运行容器">{{ runningContainers }} / {{ containers.length }}</a-descriptions-item>
            <a-descriptions-item v-if="systemInfo?.updatedAt" label="信息更新于">{{ formatTime(systemInfo.updatedAt) }}</a-descriptions-item>
          </a-descriptions>
        </a-card>
      </a-col>
      <a-col :span="16">
        <a-card size="small">
          <template #title><span class="card-title">受控端信息</span></template>
          <a-empty v-if="agentInfo.length === 0" description="暂无在线受控端" />
          <a-table
            v-else
            :data-source="agentInfo"
            :pagination="false"
            size="small"
            :scroll="{ x: 'max-content' }"
            class="auto-table"
            row-key="instanceNumber"
          >
            <a-table-column title="编号" data-index="instanceNumber" :width="80" />
            <a-table-column title="机器名" data-index="machineName" />
            <a-table-column title="IP地址" data-index="ipAddress" :width="130" />
            <a-table-column title="受控端版本" data-index="agentVersion" :width="110" />
            <a-table-column title="操作系统" data-index="osInfo" />
            <a-table-column title="GPU" data-index="gpuInfo" />
            <a-table-column title="存储根目录" data-index="storageRoot" />
            <a-table-column title="连接模式" data-index="connectMode" :width="90" />
            <a-table-column title="最后心跳" :width="150">
              <template #default="{ record }">{{ record.lastHeartbeat ? formatTime(record.lastHeartbeat) : '-' }}</template>
            </a-table-column>
          </a-table>
        </a-card>
      </a-col>
    </a-row>

    <!-- 公告 -->
    <a-card size="small" style="margin-top: 28px">
      <template #title><span class="card-title">公告</span></template>
      <a-empty v-if="announcements.length === 0" description="暂无公告" />
      <a-list v-else :data-source="announcements" size="small">
        <template #renderItem="{ item }">
          <a-list-item>
            <a-list-item-meta>
              <template #title>
                <a-tag color="purple" style="margin-right: 4px">公告</a-tag>
                {{ item.title }}
              </template>
              <template #description>
                <div>{{ item.content.length > 100 ? item.content.slice(0, 100) + '…' : item.content }}</div>
                <a-typography-text type="secondary" style="font-size: 12px">
                  {{ item.authorName ?? '系统' }} · {{ formatTime(item.createdAt) }}
                </a-typography-text>
              </template>
            </a-list-item-meta>
          </a-list-item>
        </template>
      </a-list>
    </a-card>

    <!-- 物理实例状态列表 -->
    <a-card size="small" style="margin-top: 28px">
      <template #title><span class="card-title">物理实例状态</span></template>
      <a-empty v-if="instances.length === 0" description="暂无可用实例" />
      <a-list v-else :data-source="instances.slice(0, 8)" size="small">
        <template #renderItem="{ item }">
          <a-list-item>
            <a-list-item-meta>
              <template #title>
                {{ item.instanceNumber }} - {{ item.machineName || '未命名' }}
              </template>
              <template #description>
                <a-tag :color="item.status === 'ONLINE' ? 'green' : 'default'">
                  {{ item.status === 'ONLINE' ? '在线' : '离线' }}
                </a-tag>
                <span style="color: #999; font-size: 12px; margin-left: 8px">{{ item.ipAddress || '-' }}</span>
                <span v-if="item.agentVersion" style="color: #999; font-size: 12px; margin-left: 8px">v{{ item.agentVersion }}</span>
                <span v-if="item.osInfo" style="color: #999; font-size: 12px; margin-left: 8px">{{ item.osInfo }}</span>
              </template>
            </a-list-item-meta>
          </a-list-item>
        </template>
      </a-list>
    </a-card>
  </div>
</template>

<style scoped>
/* 标题：无底色，黑字 */
.card-title {
  color: #1f1f1f;
  font-weight: 600;
}
/* 卡片无边框阴影 */
:deep(.ant-card) {
  box-shadow: none;
}
.stat-card {
  transition: transform 0.2s;
}
.stat-card:hover {
  transform: translateY(-2px);
}
/* 表头不换行自适应（受控端信息表） */
.auto-table :deep(.ant-table-thead > tr > th) {
  white-space: nowrap;
}
.auto-table :deep(.ant-table-tbody > tr > td) {
  white-space: nowrap;
}
</style>
