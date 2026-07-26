<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { message, Modal } from 'ant-design-vue'
import dayjs from 'dayjs'
import { ticketApi, type Ticket, type TicketType } from '@/api/ticket'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()

const loading = ref(false)
const tickets = ref<Ticket[]>([])
const createVisible = ref(false)
const form = reactive({ title: '', type: 'RESOURCE' as TicketType, content: '', contact: '' })

// platform-refinements #5：工单详情
const detailVisible = ref(false)
const detail = ref<Ticket | null>(null)
const history = ref<Array<{ action: string; operatorName: string; content: string; createdAt: string }>>([])

const typeOptions = [
  { value: 'RESOURCE', label: '资源申请' },
  { value: 'FAULT', label: '故障报告' },
  { value: 'SPECIAL_CONFIG', label: '特殊配置' },
  { value: 'PERMISSION', label: '权限申请' },
  { value: 'IMAGE', label: '镜像申请' },
]
const typeLabel: Record<string, string> = Object.fromEntries(typeOptions.map((t) => [t.value, t.label]))
const statusLabel: Record<string, string> = { PENDING: '待处理', CLOSED: '已关闭', CANCELLED: '已撤销' }
const statusColor: Record<string, string> = { PENDING: 'orange', CLOSED: 'green', CANCELLED: 'default' }

onMounted(load)

async function load(): Promise<void> {
  loading.value = true
  try {
    tickets.value = await ticketApi.list()
  } finally {
    loading.value = false
  }
}

async function handleCreate(): Promise<void> {
  if (!form.title || !form.content) {
    message.warning('请填写标题和内容')
    return
  }
  await ticketApi.create({ ...form })
  message.success('工单已提交')
  createVisible.value = false
  form.title = ''
  form.content = ''
  form.contact = ''
  load()
}

// D11：打开提交表单时联系方式默认填账户手机号
function openCreate(): void {
  form.contact = auth.user?.phone ?? ''
  createVisible.value = true
}

async function showDetail(t: Ticket): Promise<void> {
  detail.value = t
  detailVisible.value = true
  history.value = []
  try {
    history.value = await ticketApi.history(t.id)
  } catch {
    // 拦截器已提示
  }
}

function confirmCancel(t: Ticket): void {
  Modal.confirm({
    title: `确认撤销工单「${t.title}」？`,
    content: '撤销后工单标记为已撤销，不可恢复。',
    onOk: async () => {
      await ticketApi.cancel(t.id)
      message.success('工单已撤销')
      load()
    },
  })
}
</script>

<template>
  <div>
    <div style="display: flex; justify-content: space-between; margin-bottom: 16px">
      <a-typography-title :level="3" style="margin: 0">特需工单</a-typography-title>
      <a-button type="primary" @click="openCreate">提交工单</a-button>
    </div>

    <a-table :data-source="tickets" :loading="loading" row-key="id" :pagination="false">
      <a-table-column title="工单号" data-index="ticketNo" :width="180" />
      <a-table-column title="标题" data-index="title" />
      <a-table-column title="类型" :width="100">
        <template #default="{ record }">{{ typeLabel[record.type] }}</template>
      </a-table-column>
      <a-table-column title="提交人" data-index="submitterName" :width="100" />
      <a-table-column title="状态" :width="90">
        <template #default="{ record }">
          <a-tag :color="statusColor[record.status] || 'default'">
            {{ statusLabel[record.status] || record.status }}
          </a-tag>
        </template>
      </a-table-column>
      <a-table-column title="提交时间" :width="170">
        <template #default="{ record }">{{ dayjs(record.createdAt).format('YYYY-MM-DD HH:mm') }}</template>
      </a-table-column>
      <a-table-column title="操作" :width="80">
        <template #default="{ record }">
          <a-button type="link" size="small" @click="showDetail(record)">详情</a-button>
          <a-button
            v-if="record.status === 'PENDING' && record.submitterId === auth.user?.id"
            type="link" danger size="small" @click="confirmCancel(record)"
          >撤销</a-button>
        </template>
      </a-table-column>
    </a-table>

    <a-modal v-model:open="createVisible" title="提交工单" @ok="handleCreate">
      <a-form layout="vertical">
        <a-form-item label="标题">
          <a-input v-model:value="form.title" />
        </a-form-item>
        <a-form-item label="类型">
          <a-select v-model:value="form.type">
            <a-select-option v-for="t in typeOptions" :key="t.value" :value="t.value">{{ t.label }}</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item label="内容">
          <a-textarea v-model:value="form.content" :rows="4" />
        </a-form-item>
        <a-form-item label="联系方式">
          <a-input v-model:value="form.contact" placeholder="默认账户手机号，可修改" />
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- 工单详情（platform-refinements #5） -->
    <a-modal v-model:open="detailVisible" title="工单详情" width="640px" :footer="null">
      <a-descriptions v-if="detail" :column="1" bordered size="small">
        <a-descriptions-item label="标题">{{ detail.title }}</a-descriptions-item>
        <a-descriptions-item label="类型">{{ typeLabel[detail.type] }}</a-descriptions-item>
        <a-descriptions-item label="提交人">{{ detail.submitterName }}{{ detail.groupName ? '（' + detail.groupName + '）' : '' }}</a-descriptions-item>
        <a-descriptions-item v-if="detail.contact" label="联系方式">{{ detail.contact }}</a-descriptions-item>
        <a-descriptions-item label="状态">
          <a-tag :color="detail.status === 'PENDING' ? 'orange' : 'green'">
            {{ detail.status === 'PENDING' ? '待处理' : '已关闭' }}
          </a-tag>
        </a-descriptions-item>
        <a-descriptions-item label="提交时间">{{ dayjs(detail.createdAt).format('YYYY-MM-DD HH:mm') }}</a-descriptions-item>
        <a-descriptions-item label="内容">
          <pre style="white-space: pre-wrap; margin: 0">{{ detail.content }}</pre>
        </a-descriptions-item>
        <a-descriptions-item v-if="detail.reply" label="回复">
          <pre style="white-space: pre-wrap; margin: 0">{{ detail.reply }}</pre>
          <div style="color: #999; font-size: 12px; margin-top: 4px">
            {{ detail.replierName }} · {{ detail.repliedAt ? dayjs(detail.repliedAt).format('YYYY-MM-DD HH:mm') : '' }}
          </div>
        </a-descriptions-item>
      </a-descriptions>
      <a-typography-title v-if="history.length" :level="5" style="margin-top: 16px">处理记录</a-typography-title>
      <a-timeline v-if="history.length">
        <a-timeline-item v-for="(h, i) in history" :key="i">
          <p style="margin: 0">{{ h.action }} · {{ h.operatorName }}</p>
          <p style="color: #999; font-size: 12px; margin: 0">{{ dayjs(h.createdAt).format('YYYY-MM-DD HH:mm') }}</p>
          <p v-if="h.content" style="margin: 4px 0 0">{{ h.content }}</p>
        </a-timeline-item>
      </a-timeline>
    </a-modal>
  </div>
</template>
