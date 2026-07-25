<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { message, Modal } from 'ant-design-vue'
import dayjs from 'dayjs'
import { announcementApi, type Announcement } from '@/api/announcement'

const loading = ref(false)
const announcements = ref<Announcement[]>([])

const visible = ref(false)
const form = reactive({
  title: '',
  content: '',
  targetScope: 'ALL',
  targetId: undefined as number | undefined,
  targetRole: undefined as string | undefined,
  publishMode: 'IMMEDIATE',
  publishAt: undefined as any,
})

onMounted(load)

async function load(): Promise<void> {
  loading.value = true
  try {
    announcements.value = await announcementApi.listAll()
  } finally {
    loading.value = false
  }
}

async function handlePublish(): Promise<void> {
  if (!form.title || !form.content) {
    message.warning('请填写标题和内容')
    return
  }
  const data: Record<string, unknown> = { ...form }
  if (form.publishMode === 'SCHEDULED' && form.publishAt) {
    data.publishAt = dayjs(form.publishAt).toISOString()
  }
  await announcementApi.publish(data)
  message.success('公告已创建')
  visible.value = false
  form.title = ''
  form.content = ''
  load()
}

function confirmDelete(ann: Announcement): void {
  Modal.confirm({
    title: `确认删除公告「${ann.title}」？`,
    onOk: async () => {
      await announcementApi.delete(ann.id)
      message.success('已删除')
      load()
    },
  })
}

const scopeLabel: Record<string, string> = { ALL: '全体', GROUP: '指定课题组', ROLE: '指定角色' }
const modeLabel: Record<string, string> = { IMMEDIATE: '立即', SCHEDULED: '定时' }
</script>

<template>
  <div>
    <div style="display: flex; justify-content: space-between; margin-bottom: 16px">
      <a-typography-title :level="3" style="margin: 0">公告管理</a-typography-title>
      <a-button type="primary" @click="visible = true">发布公告</a-button>
    </div>

    <a-table :data-source="announcements" :loading="loading" row-key="id" :pagination="false">
      <a-table-column title="标题" data-index="title" />
      <a-table-column title="定向范围" :width="120">
        <template #default="{ record }">{{ scopeLabel[record.targetScope] }}</template>
      </a-table-column>
      <a-table-column title="发布方式" :width="80">
        <template #default="{ record }">{{ modeLabel[record.publishMode] }}</template>
      </a-table-column>
      <a-table-column title="状态" :width="80">
        <template #default="{ record }">
          <a-tag :color="record.status === 'PUBLISHED' ? 'green' : 'orange'">
            {{ record.status === 'PUBLISHED' ? '已发布' : '待发布' }}
          </a-tag>
        </template>
      </a-table-column>
      <a-table-column title="发布时间" :width="150">
        <template #default="{ record }">{{ record.publishAt ? dayjs(record.publishAt).format('MM-DD HH:mm') : '-' }}</template>
      </a-table-column>
      <a-table-column title="操作" :width="80">
        <template #default="{ record }">
          <a-button type="link" danger size="small" @click="confirmDelete(record)">删除</a-button>
        </template>
      </a-table-column>
    </a-table>

    <a-modal v-model:open="visible" title="发布公告" width="600px" @ok="handlePublish">
      <a-form layout="vertical">
        <a-form-item label="标题">
          <a-input v-model:value="form.title" />
        </a-form-item>
        <a-form-item label="内容">
          <a-textarea v-model:value="form.content" :rows="4" />
        </a-form-item>
        <a-form-item label="定向范围">
          <a-radio-group v-model:value="form.targetScope">
            <a-radio value="ALL">全体</a-radio>
            <a-radio value="GROUP">指定课题组</a-radio>
            <a-radio value="ROLE">指定角色</a-radio>
          </a-radio-group>
        </a-form-item>
        <a-form-item v-if="form.targetScope === 'ROLE'" label="角色">
          <a-select v-model:value="form.targetRole">
            <a-select-option value="ADMIN">管理员</a-select-option>
            <a-select-option value="MENTOR">导师</a-select-option>
            <a-select-option value="STUDENT">学生</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item label="发布方式">
          <a-radio-group v-model:value="form.publishMode">
            <a-radio value="IMMEDIATE">立即发布</a-radio>
            <a-radio value="SCHEDULED">定时发布</a-radio>
          </a-radio-group>
        </a-form-item>
        <a-form-item v-if="form.publishMode === 'SCHEDULED'" label="发布时间">
          <a-date-picker v-model:value="form.publishAt" show-time format="YYYY-MM-DD HH:mm" style="width: 100%" />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>
