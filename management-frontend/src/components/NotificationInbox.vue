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
        <!-- platform-audit-logging-ux 11.3：每条消息独立卡片，卡片间有间距 -->
        <div v-else class="msg-cards">
          <div
            v-for="item in unreadList"
            :key="item.id"
            class="msg-card"
            @click="markAsRead(item.id)"
          >
            <div class="msg-card-header">
              <a-tag :color="typeColor[item.type]">{{ typeLabel[item.type] }}</a-tag>
              <span class="msg-card-title">{{ item.title || item.content }}</span>
            </div>
            <div class="msg-card-content">
              {{ item.content.length > 80 ? item.content.slice(0, 80) + '...' : item.content }}
            </div>
            <div class="msg-card-time">{{ dayjs(item.createdAt).format('YYYY-MM-DD HH:mm') }}</div>
          </div>
        </div>
      </div>
    </template>
  </a-popover>
</template>

<style scoped>
.msg-cards {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.msg-card {
  border: 1px solid #f0f0f0;
  border-radius: 8px;
  padding: 10px 12px;
  background: #fff;
  cursor: pointer;
  transition: box-shadow 0.2s, border-color 0.2s;
}
.msg-card:hover {
  border-color: #91caff;
  box-shadow: 0 2px 8px rgba(22, 119, 255, 0.12);
}
.msg-card-header {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 6px;
}
.msg-card-title {
  font-weight: 600;
  font-size: 13px;
  color: #1f1f1f;
}
.msg-card-content {
  font-size: 13px;
  color: #555;
  line-height: 1.5;
  margin-bottom: 4px;
}
.msg-card-time {
  font-size: 12px;
  color: #999;
}
</style>
