<script setup lang="ts">
// 操作审计日志查询（platform-audit-logging-ux D11）。
// 多维度筛选（时间/操作人/姓名/角色/类型/目标/结果/客户端/导师归属/唯一编码）+
// 模糊查询 + operationNo 精确 + 列头多列排序 + 分页；热表/归档表合并查询（后端按时间跨边界 UNION）。
// 三角色可见性由后端按 mentorIdAtOp 快照过滤，前端无角色分支。
import { onMounted, reactive, ref } from 'vue'
import dayjs from 'dayjs'
import { auditLogApi, type AuditLogDto, type AuditLogPage } from '@/api/auditLog'

const loading = ref(false)
const data = ref<AuditLogDto[]>([])
const total = ref(0)
const query = reactive({
  start: undefined as string | undefined,
  end: undefined as string | undefined,
  operatorId: undefined as number | undefined,
  operatorName: undefined as string | undefined,
  operatorRole: undefined as string | undefined,
  action: undefined as string | undefined,
  targetType: undefined as string | undefined,
  targetId: undefined as string | undefined,
  result: undefined as string | undefined,
  clientInfo: undefined as string | undefined,
  mentorIdAtOp: undefined as number | undefined,
  operationNo: undefined as string | undefined,
  keyword: undefined as string | undefined,
  sortBy: 'createdAt',
  sortDir: 'desc' as 'asc' | 'desc',
  page: 1,
  size: 20,
})

const range = ref<[dayjs.Dayjs, dayjs.Dayjs] | null>(null)

const columns = [
  { title: '唯一编码', dataIndex: 'operationNo', key: 'operationNo', width: 180, sorter: true },
  { title: '时间', dataIndex: 'createdAt', key: 'createdAt', width: 170, sorter: true },
  { title: '操作人', dataIndex: 'operatorName', key: 'operatorName', sorter: true },
  { title: '角色', dataIndex: 'operatorRole', key: 'operatorRole', width: 90 },
  { title: '操作类型', dataIndex: 'action', key: 'action', sorter: true },
  { title: '目标类型', dataIndex: 'targetType', key: 'targetType', width: 120, sorter: true },
  { title: '目标ID', dataIndex: 'targetId', key: 'targetId', width: 120 },
  { title: '结果', dataIndex: 'result', key: 'result', width: 90, sorter: true },
  { title: '客户端', dataIndex: 'clientInfo', key: 'clientInfo', ellipsis: true },
  { title: '导师归属', dataIndex: 'mentorIdAtOp', key: 'mentorIdAtOp', width: 90 },
  { title: '来源', dataIndex: 'source', key: 'source', width: 80 },
  { title: '详情', key: 'detail', width: 80, fixed: 'right' as const },
]

onMounted(load)

async function load(): Promise<void> {
  loading.value = true
  try {
    const params: Record<string, unknown> = { ...query }
    if (range.value && range.value.length === 2) {
      params.start = range.value[0].toISOString()
      params.end = range.value[1].toISOString()
    } else {
      params.start = undefined
      params.end = undefined
    }
    // 清理空字符串/undefined
    Object.keys(params).forEach((k) => {
      if (params[k] === '' || params[k] === undefined || params[k] === null) delete params[k]
    })
    params.page = query.page - 1
    params.size = query.size
    const res = await auditLogApi.list(params as any)
    data.value = res.content || []
    total.value = res.totalElements || 0
  } catch {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

function onSearch(): void {
  query.page = 1
  load()
}

function onReset(): void {
  Object.assign(query, {
    operatorId: undefined, operatorName: undefined, operatorRole: undefined,
    action: undefined, targetType: undefined, targetId: undefined, result: undefined,
    clientInfo: undefined, mentorIdAtOp: undefined, operationNo: undefined, keyword: undefined,
  })
  range.value = null
  query.page = 1
  load()
}

function onTableChange(pag: any, _filters: any, sorter: any): void {
  query.page = pag.current
  query.size = pag.pageSize
  if (sorter && sorter.field) {
    query.sortBy = sorter.field
    query.sortDir = sorter.order === 'ascend' ? 'asc' : 'desc'
  }
  load()
}

const detailVisible = ref(false)
const detailRow = ref<AuditLogDto | null>(null)
function showDetail(row: AuditLogDto): void {
  detailRow.value = row
  detailVisible.value = true
}

function fmt(v?: string): string {
  return v ? dayjs(v).format('YYYY-MM-DD HH:mm:ss') : '-'
}

// operationNo 精确定位（覆盖模糊）
function onOperationNoSearch(): void {
  if (query.operationNo && query.operationNo.trim()) {
    query.page = 1
    load()
  }
}
</script>

<template>
  <div class="audit-log-view">
    <a-card title="操作审计" :bordered="false">
      <a-form layout="inline" :model="query" class="filter-bar">
        <a-form-item label="时间范围">
          <a-range-picker
            v-model:value="range"
            show-time
            format="YYYY-MM-DD HH:mm"
            :placeholder="['开始', '结束']"
          />
        </a-form-item>
        <a-form-item label="操作人ID">
          <a-input-number v-model:value="query.operatorId" placeholder="ID" :min="1" style="width: 110px" />
        </a-form-item>
        <a-form-item label="姓名">
          <a-input v-model:value="query.operatorName" placeholder="操作人姓名" allow-clear style="width: 130px" />
        </a-form-item>
        <a-form-item label="角色">
          <a-select v-model:value="query.operatorRole" placeholder="角色" allow-clear style="width: 110px">
            <a-select-option value="ADMIN">管理员</a-select-option>
            <a-select-option value="MENTOR">导师</a-select-option>
            <a-select-option value="STUDENT">学生</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item label="操作类型">
          <a-input v-model:value="query.action" placeholder="如 CONTAINER_CREATE" allow-clear style="width: 160px" />
        </a-form-item>
        <a-form-item label="目标类型">
          <a-input v-model:value="query.targetType" placeholder="如 CONTAINER" allow-clear style="width: 130px" />
        </a-form-item>
        <a-form-item label="目标ID">
          <a-input v-model:value="query.targetId" placeholder="目标ID" allow-clear style="width: 120px" />
        </a-form-item>
        <a-form-item label="结果">
          <a-select v-model:value="query.result" placeholder="结果" allow-clear style="width: 110px">
            <a-select-option value="SUCCESS">成功</a-select-option>
            <a-select-option value="FAILURE">失败</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item label="客户端">
          <a-input v-model:value="query.clientInfo" placeholder="IP/UA" allow-clear style="width: 130px" />
        </a-form-item>
        <a-form-item label="导师归属">
          <a-input-number v-model:value="query.mentorIdAtOp" placeholder="导师ID" :min="1" style="width: 110px" />
        </a-form-item>
        <a-form-item label="唯一编码">
          <a-input
            v-model:value="query.operationNo"
            placeholder="精确编码"
            allow-clear
            style="width: 180px"
            @press-enter="onOperationNoSearch"
          />
        </a-form-item>
        <a-form-item label="模糊">
          <a-input v-model:value="query.keyword" placeholder="内容/错误/姓名等" allow-clear style="width: 180px" />
        </a-form-item>
        <a-form-item>
          <a-space>
            <a-button type="primary" @click="onSearch">查询</a-button>
            <a-button @click="onReset">重置</a-button>
          </a-space>
        </a-form-item>
      </a-form>

      <a-table
        :columns="columns"
        :data-source="data"
        :loading="loading"
        row-key="id"
        :pagination="{ current: query.page, pageSize: query.size, total, showSizeChanger: true }"
        :scroll="{ x: 1500 }"
        @change="onTableChange"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'result'">
            <a-tag :color="record.result === 'SUCCESS' ? 'green' : 'red'">
              {{ record.result === 'SUCCESS' ? '成功' : '失败' }}
            </a-tag>
          </template>
          <template v-else-if="column.key === 'createdAt'">{{ fmt(record.createdAt) }}</template>
          <template v-else-if="column.key === 'source'">
            <a-tag :color="record.source === 'HOT' ? 'blue' : 'default'">
              {{ record.source === 'HOT' ? '热表' : '归档' }}
            </a-tag>
          </template>
          <template v-else-if="column.key === 'detail'">
            <a-button type="link" size="small" @click="showDetail(record)">详情</a-button>
          </template>
        </template>
      </a-table>
    </a-card>

    <a-modal v-model:open="detailVisible" title="审计记录详情" width="720px" :footer="null">
      <a-descriptions v-if="detailRow" :column="2" bordered size="small">
        <a-descriptions-item label="唯一编码" :span="2">{{ detailRow.operationNo || '-' }}</a-descriptions-item>
        <a-descriptions-item label="时间">{{ fmt(detailRow.createdAt) }}</a-descriptions-item>
        <a-descriptions-item label="来源">{{ detailRow.source === 'HOT' ? '热表' : '归档表' }}</a-descriptions-item>
        <a-descriptions-item label="操作人">{{ detailRow.operatorName || '-' }}</a-descriptions-item>
        <a-descriptions-item label="角色">{{ detailRow.operatorRole || '-' }}</a-descriptions-item>
        <a-descriptions-item label="操作类型" :span="2">{{ detailRow.action }}</a-descriptions-item>
        <a-descriptions-item label="目标类型">{{ detailRow.targetType || '-' }}</a-descriptions-item>
        <a-descriptions-item label="目标ID">{{ detailRow.targetId || '-' }}</a-descriptions-item>
        <a-descriptions-item label="结果">
          <a-tag :color="detailRow.result === 'SUCCESS' ? 'green' : 'red'">{{ detailRow.result }}</a-tag>
        </a-descriptions-item>
        <a-descriptions-item label="导师归属">{{ detailRow.mentorIdAtOp || '-' }}</a-descriptions-item>
        <a-descriptions-item label="客户端" :span="2">{{ detailRow.clientInfo || '-' }}</a-descriptions-item>
        <a-descriptions-item label="IP">{{ detailRow.ipAddress || '-' }}</a-descriptions-item>
        <a-descriptions-item label="操作内容" :span="2">
          <pre class="content-pre">{{ detailRow.content || '-' }}</pre>
        </a-descriptions-item>
        <a-descriptions-item label="错误信息" :span="2">
          <pre class="content-pre">{{ detailRow.errorMessage || '-' }}</pre>
        </a-descriptions-item>
      </a-descriptions>
    </a-modal>
  </div>
</template>

<style scoped>
.audit-log-view {
  padding: 16px;
}
.filter-bar {
  margin-bottom: 16px;
  gap: 8px;
  flex-wrap: wrap;
}
.content-pre {
  white-space: pre-wrap;
  word-break: break-all;
  margin: 0;
  max-height: 200px;
  overflow: auto;
  font-size: 12px;
}
</style>
