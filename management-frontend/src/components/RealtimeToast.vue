<script setup lang="ts">
// 右下角实时变动 Toast（platform-env-ota-realtime D8）。
// 订阅 SSE container.changed / ticket.changed / storage.changed 事件，
// AntD notification 右下角堆叠、5s 自动消失、点击跳转 link。
// 同事件去抖（1.5s 内同类型只弹一次），避免短时风暴。
import { onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { notification } from 'ant-design-vue'
import { getSseClient, type SseEventType } from '@/utils/sse'

interface ToastPayload {
  type: string
  title?: string
  message?: string
  link?: string | null
}

const router = useRouter()

const typeLabel: Record<string, string> = {
  'container.changed': '容器变动',
  'ticket.changed': '工单变动',
  'storage.changed': '存储池变动',
}

const typeColor: Record<string, string> = {
  'container.changed': 'blue',
  'ticket.changed': 'orange',
  'storage.changed': 'cyan',
}

// 同事件去抖：记录最近一次展示时间，1.5s 内同类型不重复弹出
const lastShown = new Map<string, number>()
const DEBOUNCE_MS = 1500

let unsubs: Array<() => void> = []

function showToast(eventType: SseEventType, data: unknown): void {
  const payload = (data ?? {}) as ToastPayload
  const now = Date.now()
  const last = lastShown.get(eventType) ?? 0
  if (now - last < DEBOUNCE_MS) {
    return // 去抖：同类型短时风暴只弹一次（未读消息收件箱仍记录全部）
  }
  lastShown.set(eventType, now)

  const title = payload.title || typeLabel[eventType] || '新消息'
  notification.open({
    message: title,
    description: payload.message || '',
    placement: 'bottomRight',
    duration: 5,
    type: 'info',
    onClick: () => {
      if (payload.link) {
        router.push(payload.link)
      }
    },
  })
  // 标签色提示（AntD notification 无原生 tag，title 中已含类型语义）
  void typeColor
}

onMounted(() => {
  const sse = getSseClient()
  for (const ev of ['container.changed', 'ticket.changed', 'storage.changed'] as SseEventType[]) {
    unsubs.push(sse.on(ev, (data) => showToast(ev, data)))
  }
})

onUnmounted(() => {
  unsubs.forEach((u) => u())
  unsubs = []
})
</script>

<template>
  <!-- 无 UI，仅订阅 SSE 弹 Toast -->
</template>
