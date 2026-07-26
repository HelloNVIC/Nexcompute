<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import dayjs from 'dayjs'
import { ticketApi, type Ticket, type TicketType } from '@/api/ticket'

const loading = ref(false)
const tickets = ref<Ticket[]>([])
const filterStatus = ref<string | undefined>()
const filterType = ref<TicketType | undefined>()

const replyVisible = ref(false)
const replyForm = reactive({ id: 0, reply: '' })

// D11：工单详情（已处理/待处理两张表）
const detailVisible = ref(false)
const pendingTickets = computed(() => tickets.value.filter((t) => t.status === 'PENDING'))
const closedTickets = computed(() => tickets.value.filter((t) => t.status === 'CLOSED'))

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

const filtered = computed(() => {
  return tickets.value.filter((t) => {
    if (filterStatus.value && t.status !== filterStatus.value) return false
    if (filterType.value && t.type !== filterType.value) return false
    return true
  })
})

function showReply(ticket: Ticket): void {
  replyForm.id = ticket.id
  replyForm.reply = ''
  replyVisible.value = true
}

async function handleClose(): Promise<void> {
  if (!replyForm.reply) {
    message.warning('请输入回复内容')
    return
  }
  await ticketApi.close(replyForm.id, replyForm.reply)
  message.success('工单已回复关闭')
  replyVisible.value = false
  load()
}
</script>

<template>
  <div>
    <a-typography-title :level="3">工单管理</a-typography-title>

    <a-space style="margin-bottom: 16px">
      <a-select v-model:value="filterStatus" placeholder="按状态筛选" style="width: 150px" allow-clear>
        <a-select-option value="PENDING">待处理</a-select-option>
        <a-select-option value="CLOSED">已关闭</a-select-option>
        <a-select-option value="CANCELLED">已撤销</a-select-option>
      </a-select>
      <a-select v-model:value="filterType" placeholder="按类型筛选" style="width: 150px" allow-clear>
        <a-select-option v-for="t in typeOptions" :key="t.value" :value="t.value">{{ t.label }}</a-select-option>
      </a-select>
      <a-button @click="detailVisible = true">工单详情</a-button>
    </a-space>

    <a-table :data-source="filtered" :loading="loading" row-key="id" :pagination="false">
      <a-table-column title="工单号" data-index="ticketNo" :width="180" :sorter="(a: Ticket, b: Ticket) => (a.ticketNo||'').localeCompare(b.ticketNo||'')" />
      <a-table-column title="标题" data-index="title" :sorter="(a: Ticket, b: Ticket) => a.title.localeCompare(b.title)" />
      <a-table-column title="类型" :width="100" :sorter="(a: Ticket, b: Ticket) => a.type.localeCompare(b.type)">
        <template #default="{ record }">{{ typeLabel[record.type] }}</template>
      </a-table-column>
      <a-table-column title="提交人" data-index="submitterName" :width="100" :sorter="(a: Ticket, b: Ticket) => a.submitterName.localeCompare(b.submitterName)" />
      <a-table-column title="课题组" data-index="groupName" :width="120" />
      <a-table-column title="状态" :width="90" :sorter="(a: Ticket, b: Ticket) => a.status.localeCompare(b.status)">
        <template #default="{ record }">
          <a-tag :color="statusColor[record.status] || 'default'">{{ statusLabel[record.status] || record.status }}</a-tag>
        </template>
      </a-table-column>
      <a-table-column title="提交时间" :width="160" :sorter="(a: Ticket, b: Ticket) => a.createdAt.localeCompare(b.createdAt)">
        <template #default="{ record }">{{ dayjs(record.createdAt).format('YYYY-MM-DD HH:mm') }}</template>
      </a-table-column>
      <a-table-column title="操作" :width="100">
        <template #default="{ record }">
          <a-button v-if="record.status === 'PENDING'" type="link" size="small" @click="showReply(record)">回复关闭</a-button>
        </template>
      </a-table-column>
    </a-table>

    <a-modal v-model:open="replyVisible" title="回复并关闭工单" @ok="handleClose">
      <a-textarea v-model:value="replyForm.reply" :rows="4" placeholder="输入回复内容" />
    </a-modal>

    <!-- D11：工单详情（已处理/待处理两张表） -->
    <a-drawer v-model:open="detailVisible" title="工单详情" width="900px" :footer="null">
      <a-typography-title :level="5">待处理（{{ pendingTickets.length }}）</a-typography-title>
      <a-table :data-source="pendingTickets" row-key="id" :pagination="{ pageSize: 5 }" size="small">
        <a-table-column title="工单号" data-index="ticketNo" :width="170" />
        <a-table-column title="标题" data-index="title" />
        <a-table-column title="类型" :width="90">
          <template #default="{ record }">{{ typeLabel[record.type] }}</template>
        </a-table-column>
        <a-table-column title="提交人" data-index="submitterName" :width="90" />
        <a-table-column title="联系方式" data-index="contact" :width="120" />
        <a-table-column title="提交时间" :width="150">
          <template #default="{ record }">{{ dayjs(record.createdAt).format('MM-DD HH:mm') }}</template>
        </a-table-column>
        <a-table-column title="操作" :width="90">
          <template #default="{ record }">
            <a-button type="link" size="small" @click="showReply(record)">回复关闭</a-button>
          </template>
        </a-table-column>
      </a-table>

      <a-typography-title :level="5" style="margin-top: 24px">已处理（{{ closedTickets.length }}）</a-typography-title>
      <a-table :data-source="closedTickets" row-key="id" :pagination="{ pageSize: 5 }" size="small">
        <a-table-column title="工单号" data-index="ticketNo" :width="170" />
        <a-table-column title="标题" data-index="title" />
        <a-table-column title="类型" :width="90">
          <template #default="{ record }">{{ typeLabel[record.type] }}</template>
        </a-table-column>
        <a-table-column title="提交人" data-index="submitterName" :width="90" />
        <a-table-column title="联系方式" data-index="contact" :width="120" />
        <a-table-column title="回复" :width="200">
          <template #default="{ record }">
            <span v-if="record.reply">{{ record.reply.length > 30 ? record.reply.slice(0, 30) + '...' : record.reply }}</span>
            <span v-else style="color: #999">-</span>
          </template>
        </a-table-column>
        <a-table-column title="回复时间" :width="150">
          <template #default="{ record }">{{ record.repliedAt ? dayjs(record.repliedAt).format('MM-DD HH:mm') : '-' }}</template>
        </a-table-column>
      </a-table>
    </a-drawer>
  </div>
</template>
