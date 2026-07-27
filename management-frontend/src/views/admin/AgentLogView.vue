<script setup lang="ts">
// 受控端日志查看（platform-audit-logging-ux D8）。
// 选物理实例 -> 选日期 -> 查看受控端日志（尾行刷新 + 下载）。现拉现返回，不入库。
import { onMounted, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import dayjs from 'dayjs'
import { instanceApi, type PhysicalInstance } from '@/api/instance'
import { agentLogApi, type AgentLogFile } from '@/api/agentLog'

const loading = ref(false)
const tailLoading = ref(false)
const instances = ref<PhysicalInstance[]>([])
const selectedInstance = ref<number | undefined>(undefined)
const files = ref<AgentLogFile[]>([])
const selectedDate = ref<dayjs.Dayjs>(dayjs())
const tailLines = ref(500)
const content = ref('')

onMounted(async () => {
  try {
    instances.value = await instanceApi.list()
  } catch {
    // 拦截器已提示
  }
})

watch(selectedInstance, async (id) => {
  files.value = []
  content.value = ''
  if (!id) return
  loading.value = true
  try {
    files.value = await agentLogApi.listFiles(id)
  } catch {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
})

async function loadTail(): Promise<void> {
  if (!selectedInstance.value) {
    message.warning('请先选择物理实例')
    return
  }
  tailLoading.value = true
  try {
    const res = await agentLogApi.tail(
      selectedInstance.value,
      selectedDate.value.format('YYYY-MM-DD'),
      tailLines.value,
    )
    content.value = res.content || '(无内容)'
  } catch {
    // 拦截器已提示
  } finally {
    tailLoading.value = false
  }
}

function downloadFile(file: AgentLogFile): void {
  if (!selectedInstance.value) return
  // 经尾行接口拉取大行数模拟"下载"（整文件下载暂不做，R6）
  agentLogApi
    .tail(selectedInstance.value, file.name.replace(/^agent-/, '').replace(/\.log$/, ''), 5000)
    .then((res) => {
      const blob = new Blob([res.content || ''], { type: 'text/plain;charset=utf-8' })
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = file.name
      a.click()
      URL.revokeObjectURL(url)
    })
    .catch(() => {
      // 拦截器已提示
    })
}

function fmtSize(n: number): string {
  if (n < 1024) return n + ' B'
  if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB'
  return (n / 1024 / 1024).toFixed(1) + ' MB'
}
</script>

<template>
  <div class="agent-log-view">
    <a-card title="受控端日志查看" :bordered="false">
      <a-space class="toolbar" wrap>
        <a-select
          v-model:value="selectedInstance"
          placeholder="选择物理实例"
          style="width: 220px"
          :options="instances.map((i) => ({ label: i.instanceNumber, value: i.id }))"
        />
        <a-date-picker v-model:value="selectedDate" format="YYYY-MM-DD" />
        <a-input-number v-model:value="tailLines" :min="50" :max="5000" :step="50" addon-after="行" />
        <a-button type="primary" :loading="tailLoading" :disabled="!selectedInstance" @click="loadTail">
          查看尾行
        </a-button>
      </a-space>

      <a-row :gutter="16" class="body-row">
        <a-col :span="8">
          <a-card title="日志文件列表" size="small" :loading="loading">
            <a-empty v-if="!files.length" description="无日志文件" />
            <a-list v-else size="small" :data-source="files">
              <template #renderItem="{ item }">
                <a-list-item>
                  <a-list-item-meta :title="item.name" :description="`${fmtSize(item.size)} · ${item.modTime}`" />
                  <template #actions>
                    <a-button type="link" size="small" @click="downloadFile(item)">下载</a-button>
                  </template>
                </a-list-item>
              </template>
            </a-list>
          </a-card>
        </a-col>
        <a-col :span="16">
          <a-card title="日志内容（尾行）" size="small">
            <pre class="log-content">{{ content || '(点击"查看尾行"加载)' }}</pre>
          </a-card>
        </a-col>
      </a-row>
    </a-card>
  </div>
</template>

<style scoped>
.agent-log-view {
  padding: 16px;
}
.toolbar {
  margin-bottom: 16px;
}
.body-row {
  margin-top: 8px;
}
.log-content {
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 540px;
  overflow: auto;
  background: #1e1e1e;
  color: #d4d4d4;
  padding: 12px;
  border-radius: 4px;
  font-size: 12px;
  font-family: Consolas, Menlo, monospace;
  margin: 0;
}
</style>
