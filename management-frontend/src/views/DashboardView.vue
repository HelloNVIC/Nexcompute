<script setup lang="ts">
import { onMounted, ref, computed } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { announcementApi, type Announcement } from '@/api/announcement'
import { instanceApi, type PhysicalInstance } from '@/api/instance'
import { containerApi, type Container } from '@/api/container'
import { imageApi } from '@/api/image'
import { storagePoolApi, type StoragePool } from '@/api/storagePool'
import { groupApi, type UserInfoDto } from '@/api/group'
import { userApi } from '@/api/user'
import { allocationApi } from '@/api/allocation'

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

const roleLabel: Record<string, string> = { ADMIN: '管理员', MENTOR: '导师', STUDENT: '学生' }

const onlineInstances = computed(() => instances.value.filter((i) => i.status === 'ONLINE').length)

onMounted(async () => {
  try {
    const list = await announcementApi.list().catch(() => [])
    announcements.value = (list || []).filter((a) => a.status === 'PUBLISHED').slice(0, 6)
  } catch { /* 静默 */ }
  try { instances.value = await instanceApi.list() } catch { /* */ }
  try { containers.value = await containerApi.list() } catch { /* */ }
  try { pools.value = await storagePoolApi.list() } catch { /* */ }

  if (auth.role === 'ADMIN') {
    try { images.value = await imageApi.list() } catch { /* */ }
    try { groupsCount.value = (await groupApi.list()).length } catch { /* */ }
    try { usersCount.value = (await userApi.list({ page: 0, size: 1 })).totalElements } catch { /* */ }
  } else if (auth.role === 'MENTOR') {
    const g = await groupApi.my().catch(() => null)
    if (g) {
      try { students.value = await groupApi.students(g.id) } catch { /* */ }
    }
    try { groupAllocCount.value = (await allocationApi.groupAllocations()).length } catch { /* */ }
  }
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
    c.push({ label: '容器', value: containers.value.length, color: '#52c41a' })
    c.push({ label: '镜像', value: images.value.length, color: '#722ed1' })
    c.push({ label: '存储池', value: pools.value.length, color: '#13c2c2' })
    c.push({ label: '课题组', value: groupsCount.value, color: '#eb2f96' })
    c.push({ label: '用户', value: usersCount.value, color: '#fa8c16' })
  } else if (auth.role === 'MENTOR') {
    c.push({ label: '课题组成员', value: students.value.length, color: '#1677ff' })
    c.push({ label: '已分配实例', value: groupAllocCount.value, color: '#52c41a' })
    c.push({ label: '组内容器', value: containers.value.length, color: '#722ed1' })
    c.push({ label: '可访问存储池', value: pools.value.length, color: '#13c2c2' })
  } else {
    c.push({ label: '我的容器', value: containers.value.length, color: '#1677ff' })
    c.push({ label: '我的存储池', value: pools.value.length, color: '#52c41a' })
    c.push({ label: '可用实例', value: instances.value.length, sub: `在线 ${onlineInstances.value}`, color: '#722ed1' })
  }
  return c
})
</script>

<template>
  <div>
    <!-- 顶部：用户信息 -->
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
        <a-typography-text type="secondary" style="font-size: 12px">合算 Nexcompute 管理端 v0.1.0</a-typography-text>
      </div>
    </a-card>

    <!-- 统计卡片（按角色） -->
    <a-row :gutter="16" style="margin-top: 16px">
      <a-col v-for="s in stats" :key="s.label" :xs="12" :sm="8" :md="6" :lg="4">
        <a-card size="small" :bordered="true">
          <a-statistic :title="s.label" :value="s.value" :value-style="{ color: s.color }">
            <template v-if="s.sub" #suffix>
              <span style="font-size: 12px; color: #999">{{ s.sub }}</span>
            </template>
          </a-statistic>
        </a-card>
      </a-col>
    </a-row>

    <!-- 公告 + 物理实例状态 -->
    <a-row :gutter="16" style="margin-top: 16px">
      <a-col :span="14">
        <a-card title="公告">
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
      </a-col>
      <a-col :span="10">
        <a-card title="物理实例状态">
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
                    <span style="color: #999; font-size: 12px">{{ item.ipAddress || '-' }}</span>
                  </template>
                </a-list-item-meta>
              </a-list-item>
            </template>
          </a-list>
        </a-card>
      </a-col>
    </a-row>
  </div>
</template>
