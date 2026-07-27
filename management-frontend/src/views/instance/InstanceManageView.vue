<script setup lang="ts">
import { onMounted, onUnmounted, reactive, ref } from 'vue'
import { message, Modal } from 'ant-design-vue'
import dayjs from 'dayjs'
import { instanceApi, type PhysicalInstance } from '@/api/instance'
import { getSseClient } from '@/utils/sse'

const loading = ref(false)
const instances = ref<PhysicalInstance[]>([])

const psModalVisible = ref(false)
const psInstance = ref<PhysicalInstance | null>(null)
const psCommand = ref('')
const psOutput = ref('')
const psLoading = ref(false)

const numberModalVisible = ref(false)
const numberForm = reactive({ id: 0, number: '' })

onMounted(load)

// platform-audit-logging-ux 9.3：订阅 instance SSE 事件，实时刷新实例状态
let offInstanceSse: (() => void) | null = null
onMounted(() => {
  offInstanceSse = getSseClient().on('instance', (data) => {
    const ev = data as { instanceId?: number; status?: string; instanceNumber?: string }
    if (!ev || !ev.instanceId) return
    const target = instances.value.find((i) => i.id === ev.instanceId)
    if (target && ev.status && target.status !== ev.status) {
      target.status = ev.status
    } else if (ev.instanceNumber && !target) {
      // 新注册实例：重新加载列表
      load()
    }
  })
})
onUnmounted(() => {
  offInstanceSse?.()
})

async function load(): Promise<void> {
  loading.value = true
  try {
    instances.value = await instanceApi.list()
  } catch {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

function showPowerShell(instance: PhysicalInstance): void {
  psInstance.value = instance
  psCommand.value = ''
  psOutput.value = ''
  psModalVisible.value = true
}

async function runPowerShell(): Promise<void> {
  if (!psInstance.value || !psCommand.value) return
  psLoading.value = true
  psOutput.value = ''
  try {
    const result = await instanceApi.powershell(psInstance.value.id, psCommand.value)
    psOutput.value = result.output || result.error || '(无输出)'
  } catch {
    // 拦截器已提示
  } finally {
    psLoading.value = false
  }
}

function showEditNumber(instance: PhysicalInstance): void {
  numberForm.id = instance.id
  numberForm.number = instance.instanceNumber
  numberModalVisible.value = true
}

async function saveNumber(): Promise<void> {
  try {
    await instanceApi.updateNumber(numberForm.id, numberForm.number)
    message.success('编号已修改')
    numberModalVisible.value = false
    load()
  } catch {
    // 拦截器已提示
  }
}

function confirmRestart(instance: PhysicalInstance): void {
  Modal.confirm({
    title: `确认重启 ${instance.instanceNumber}？`,
    content: '机器将在 5 秒后重启',
    onOk: async () => {
      await instanceApi.restart(instance.id)
      message.success('重启指令已发送')
    },
  })
}

function confirmScreenOff(instance: PhysicalInstance): void {
  Modal.confirm({
    title: `确认息屏 ${instance.instanceNumber}？`,
    onOk: async () => {
      await instanceApi.screenOff(instance.id)
      message.success('息屏指令已发送')
    },
  })
}
</script>

<template>
  <div>
    <a-typography-title :level="3">物理实例管理</a-typography-title>
    <a-table :data-source="instances" :loading="loading" row-key="id" :pagination="false">
      <a-table-column title="编号" data-index="instanceNumber" :width="80" />
      <a-table-column title="机器名" data-index="machineName" />
      <a-table-column title="IP" data-index="ipAddress" :width="140" />
      <a-table-column title="GPU" :width="220">
        <template #default="{ record }">
          <a-tooltip v-if="record.gpuInfo" :title="record.gpuInfo">
            <span>{{ record.gpuInfo.length > 30 ? record.gpuInfo.slice(0, 30) + '…' : record.gpuInfo }}</span>
          </a-tooltip>
          <span v-else style="color: #ccc">-</span>
        </template>
      </a-table-column>
      <a-table-column title="连接模式" :width="100">
        <template #default="{ record }">
          <a-tag>{{ record.connectMode === 'direct' ? '直连' : '穿透' }}</a-tag>
        </template>
      </a-table-column>
      <a-table-column title="受控端版本" :width="110">
        <template #default="{ record }">
          <span v-if="record.agentVersion">{{ record.agentVersion }}</span>
          <span v-else style="color: #ccc">-</span>
        </template>
      </a-table-column>
      <a-table-column title="状态" :width="80">
        <template #default="{ record }">
          <a-badge :status="record.status === 'ONLINE' ? 'success' : 'error'"
                   :text="record.status === 'ONLINE' ? '在线' : '离线'" />
        </template>
      </a-table-column>
      <a-table-column title="最后心跳" :width="170">
        <template #default="{ record }">
          {{ record.lastHeartbeat ? dayjs(record.lastHeartbeat).format('MM-DD HH:mm:ss') : '-' }}
        </template>
      </a-table-column>
      <a-table-column title="操作" :width="320">
        <template #default="{ record }">
          <a-button type="link" size="small" @click="showEditNumber(record)">改编号</a-button>
          <a-button type="link" size="small" @click="confirmRestart(record)">重启</a-button>
          <a-button type="link" size="small" @click="confirmScreenOff(record)">息屏</a-button>
          <a-button type="link" size="small" @click="showPowerShell(record)">PowerShell</a-button>
        </template>
      </a-table-column>
    </a-table>

    <!-- PowerShell 执行对话框 -->
    <a-modal v-model:open="psModalVisible" :title="`PowerShell - ${psInstance?.instanceNumber}`" width="700px" :footer="null">
      <div style="display: flex; gap: 8px; width: 100%; margin-bottom: 12px">
        <a-input v-model:value="psCommand" placeholder="输入 PowerShell 命令" @keyup.enter="runPowerShell" />
        <a-button type="primary" :loading="psLoading" @click="runPowerShell">执行</a-button>
      </div>
      <a-typography-paragraph type="secondary">输出：</a-typography-paragraph>
      <pre style="background: #1e1e1e; color: #d4d4d4; padding: 12px; border-radius: 4px; max-height: 300px; overflow: auto; white-space: pre-wrap">{{ psOutput || '(尚未执行)' }}</pre>
    </a-modal>

    <!-- 编号修改对话框 -->
    <a-modal v-model:open="numberModalVisible" title="修改物理机编号" @ok="saveNumber">
      <a-form layout="vertical">
        <a-form-item label="新编号（全系统唯一）">
          <a-input v-model:value="numberForm.number" />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>
