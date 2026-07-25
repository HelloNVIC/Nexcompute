<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { BellOutlined } from '@ant-design/icons-vue'
import dayjs from 'dayjs'
import { notificationApi, type NotificationMessage } from '@/api/notification'
import { getSseClient } from '@/utils/sse'

const unreadCount = ref(0)
const unreadList = ref<NotificationMessage[]>([])
const visible = ref(false)

let unsubSse: (() => void) | null = null

const typeLabel: Record<string, string> = {
  CONTAINER: '容器', STORAGE_POOL: '存储池', TICKET: '工单', ANNOUNCEMENT: '公告',
}
const typeColor: Record<string, string> = {
  CONTAINER: 'blue', STORAGE_POOL: 'cyan', TICKET: 'orange', ANNOUNCEMENT: 'purple',
}

onMounted(async () => {
  await refresh()
  // SSE 实时接收通知
  const sse = getSseClient()
  unsubSse = sse.on('notification', () => {
    refresh()
  })
})

onUnmounted(() => {
  unsubSse?.()
})

async function refresh(): Promise<void> {
  try {
    const [count, list] = await Promise.all([
      notificationApi.unreadCount(),
      notificationApi.unread(),
    ])
    unreadCount.value = count.count
    unreadList.value = list
  } catch {
    // 拦截器已提示
  }
}

async function markAsRead(id: number): Promise<void> {
  await notificationApi.markAsRead(id)
  await refresh()
}

async function markAllAsRead(): Promise<void> {
  await notificationApi.markAllAsRead()
  await refresh()
}
</script>

<template>
  <a-popover v-model:open="visible" trigger="click" placement="bottomRight" :width="520">
    <a-badge :count="unreadCount" :offset="[-2, 2]">
      <BellOutlined style="font-size: 18px; cursor: pointer" />
    </a-badge>
    <template #content>
      <div style="width: 480px; max-height: 400px; overflow-y: auto">
        <div style="display: flex; justify-content: space-between; margin-bottom: 8px">
          <a-typography-text strong>未读消息 ({{ unreadCount }})</a-typography-text>
          <a-button v-if="unreadCount > 0" type="link" size="small" @click="markAllAsRead">全部已读</a-button>
        </div>
        <a-empty v-if="unreadList.length === 0" description="暂无未读消息" />
        <a-list v-else :data-source="unreadList" size="small">
          <template #renderItem="{ item }">
            <a-list-item @click="markAsRead(item.id)" style="cursor: pointer; padding: 8px 0">
              <a-list-item-meta>
                <template #title>
                  <a-tag :color="typeColor[item.type]" style="margin-right: 4px">{{ typeLabel[item.type] }}</a-tag>
                  {{ item.title || item.content }}
                </template>
                <template #description>
                  {{ item.content.length > 50 ? item.content.slice(0, 50) + '...' : item.content }}
                  <br />
                  <a-typography-text type="secondary" style="font-size: 12px">
                    {{ dayjs(item.createdAt).format('MM-DD HH:mm') }}
                  </a-typography-text>
                </template>
              </a-list-item-meta>
            </a-list-item>
          </template>
        </a-list>
      </div>
    </template>
  </a-popover>
</template>
