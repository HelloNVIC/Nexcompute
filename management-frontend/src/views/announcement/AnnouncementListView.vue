<script setup lang="ts">
import { onMounted, ref } from 'vue'
import dayjs from 'dayjs'
import { announcementApi, type Announcement } from '@/api/announcement'

const loading = ref(false)
const announcements = ref<Announcement[]>([])
const contentVisible = ref(false)
const current = ref<Announcement | null>(null)

onMounted(load)

async function load(): Promise<void> {
  loading.value = true
  try {
    announcements.value = await announcementApi.list()
  } finally {
    loading.value = false
  }
}

function showContent(ann: Announcement): void {
  current.value = ann
  contentVisible.value = true
}
</script>

<template>
  <div>
    <a-typography-title :level="3">公告</a-typography-title>
    <a-list :data-source="announcements" :loading="loading" bordered>
      <template #renderItem="{ item }">
        <a-list-item @click="showContent(item)" style="cursor: pointer">
          <a-list-item-meta :title="item.title">
            <template #description>
              发布者：{{ item.authorName || '系统' }} · {{ dayjs(item.publishAt || item.createdAt).format('YYYY-MM-DD HH:mm') }}
              <div style="color: #999; font-size: 12px; margin-top: 2px">
                {{ (item.content || '').length > 60 ? item.content.slice(0, 60) + '…' : item.content }}
              </div>
            </template>
          </a-list-item-meta>
        </a-list-item>
      </template>
    </a-list>

    <a-modal v-model:open="contentVisible" :title="current?.title" :footer="null" width="600px">
      <div v-if="current">
        <a-typography-paragraph type="secondary">
          发布人：{{ current.authorName }} | 时间：{{ dayjs(current.publishAt || current.createdAt).format('YYYY-MM-DD HH:mm') }}
        </a-typography-paragraph>
        <a-divider />
        <div style="white-space: pre-wrap">{{ current.content }}</div>
      </div>
    </a-modal>
  </div>
</template>
