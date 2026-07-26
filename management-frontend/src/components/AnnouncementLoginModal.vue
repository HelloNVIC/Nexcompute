<script setup lang="ts">
// 登录公告中央弹窗（platform-env-ota-realtime D9）。
// 导师/管理员登录后拉取定向其且未读的公告，屏幕中央 a-modal 展示。
// "已读"：标记该公告已读并展示下一条；"下次再说"：localStorage 记 dismissedAnnouncements + 本会话不再弹。
import { computed, onMounted, ref } from 'vue'
import { announcementApi, type AnnouncementBanner } from '@/api/announcement'
import { notificationApi } from '@/api/notification'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()

const banners = ref<AnnouncementBanner[]>([])
const currentIndex = ref(0)

const DISMISSED_KEY = 'dismissedAnnouncements'

const current = computed<AnnouncementBanner | null>(() =>
  currentIndex.value < banners.value.length ? banners.value[currentIndex.value] : null,
)
const visible = computed(() => current.value !== null)

function getDismissed(): number[] {
  try {
    return JSON.parse(localStorage.getItem(DISMISSED_KEY) ?? '[]') as number[]
  } catch {
    return []
  }
}

function setDismissed(ids: number[]): void {
  localStorage.setItem(DISMISSED_KEY, JSON.stringify(ids))
}

onMounted(async () => {
  // 仅导师/管理员登录后弹窗（学生公告走既有"公告查看"流程）
  if (auth.role !== 'MENTOR' && auth.role !== 'ADMIN') return
  try {
    const list = await announcementApi.loginBanner()
    const dismissed = getDismissed()
    banners.value = list.filter((b) => !dismissed.includes(b.announcementId))
    currentIndex.value = 0
  } catch {
    // 拦截器已提示
  }
})

async function markRead(): Promise<void> {
  const cur = current.value
  if (!cur) return
  try {
    await notificationApi.markAsRead(cur.notificationId)
  } catch {
    // 标记失败不阻断翻页
  }
  currentIndex.value++
}

function dismiss(): void {
  const cur = current.value
  if (!cur) return
  const dismissed = getDismissed()
  if (!dismissed.includes(cur.announcementId)) {
    dismissed.push(cur.announcementId)
    setDismissed(dismissed)
  }
  currentIndex.value++
}
</script>

<template>
  <a-modal
    :open="visible"
    :title="current?.title || '系统公告'"
    :centered="true"
    :mask-closable="false"
    :closable="false"
    :footer="null"
    width="560px"
  >
    <template v-if="current">
      <div class="banner-meta">
        <a-tag v-if="current.authorName" color="blue">{{ current.authorName }}</a-tag>
        <span v-if="current.publishedAt" class="banner-time">{{ current.publishedAt.slice(0, 16).replace('T', ' ') }}</span>
      </div>
      <a-typography-paragraph style="white-space: pre-wrap; margin-top: 12px">
        {{ current.content }}
      </a-typography-paragraph>
      <div class="banner-footer">
        <span class="banner-count" v-if="banners.length > 1">
          {{ currentIndex + 1 }} / {{ banners.length }}
        </span>
        <a-space>
          <a-button @click="dismiss">下次再说</a-button>
          <a-button type="primary" @click="markRead">已读</a-button>
        </a-space>
      </div>
    </template>
  </a-modal>
</template>

<style scoped>
.banner-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  color: rgba(0, 0, 0, 0.45);
  font-size: 13px;
}
.banner-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 16px;
}
.banner-count {
  color: rgba(0, 0, 0, 0.45);
  font-size: 13px;
}
</style>
