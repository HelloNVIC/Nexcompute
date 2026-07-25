<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { message, Modal } from 'ant-design-vue'
import dayjs from 'dayjs'
import {
  allocationApi,
  type RegistrationLink,
  type MachineAllocation,
} from '@/api/allocation'
import { groupApi, type UserInfoDto, type ResearchGroup } from '@/api/group'
import { instanceApi, type PhysicalInstance } from '@/api/instance'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const isAdmin = computed(() => auth.role === 'ADMIN')

const links = ref<RegistrationLink[]>([])
const allocations = ref<MachineAllocation[]>([])
const students = ref<UserInfoDto[]>([])
const groups = ref<ResearchGroup[]>([])
const instances = ref<PhysicalInstance[]>([])
const myGroupId = ref<number | undefined>()
// 课题组已分配资源（platform-refinements #4）
const groupAllocations = ref<Array<{ groupId: number; groupName: string; instanceId: number; instanceNumber: string; machineName?: string; allocatedAt: string }>>([])

const linkModalVisible = ref(false)
const linkForm = reactive({ remainingCount: 10, expireAt: dayjs().add(30, 'day') })

// 导师分配表单（platform-refinements 8.5：物理实例多选 + 单容器内存上限）
const mentorAllocForm = reactive({
  studentId: undefined as number | undefined,
  instanceIds: [] as number[],
  perContainerMemoryMb: undefined as number | undefined,
})

// 管理员按课题组分配表单（platform-refinements #2：物理实例多选）
const adminAllocForm = reactive({
  groupId: undefined as number | undefined,
  instanceIds: [] as number[],
})

onMounted(load)

async function load(): Promise<void> {
  try {
    instances.value = await instanceApi.list()
    if (isAdmin.value) {
      // 管理员：课题组维度
      groups.value = await groupApi.list().catch(() => [])
      groupAllocations.value = await allocationApi.groupAllocations().catch(() => [])
      allocations.value = []
    } else {
      // 导师：注册链接 + 自己分配的机器 + 本课题组已分配实例（platform-refinements #7）
      links.value = await allocationApi.myLinks()
      allocations.value = await allocationApi.myMachines()
      groupAllocations.value = await allocationApi.groupAllocations().catch(() => [])
      const group = await groupApi.my().catch(() => null)
      if (group) {
        myGroupId.value = group.id
        students.value = await groupApi.students(group.id)
      }
    }
  } catch {
    // 拦截器已提示
  }
}

const pageTitle = computed(() => (isAdmin.value ? '课题组资源分配' : '学生资源分配'))

const registerUrl = (token: string) => `${window.location.origin}/register?token=${token}`

const statusLabel: Record<string, string> = {
  ACTIVE: '有效', REVOKED: '已作废', EXHAUSTED: '已耗尽', EXPIRED: '已过期',
}
const statusColor: Record<string, string> = {
  ACTIVE: 'green', REVOKED: 'red', EXHAUSTED: 'orange', EXPIRED: 'gray',
}

async function createLink(): Promise<void> {
  try {
    await allocationApi.createLink({
      remainingCount: linkForm.remainingCount,
      expireAt: linkForm.expireAt.toISOString(),
    })
    message.success('注册链接已创建')
    linkModalVisible.value = false
    load()
  } catch {
    // 拦截器已提示
  }
}

function confirmRevoke(token: string): void {
  Modal.confirm({
    title: '确认作废此注册链接？',
    content: '作废后持有链接者将无法注册',
    onOk: async () => {
      await allocationApi.revokeLink(token)
      message.success('链接已作废')
      load()
    },
  })
}

function copyLink(token: string): void {
  navigator.clipboard.writeText(registerUrl(token))
  message.success('链接已复制')
}

// 导师为学生分配物理实例（多选）+ 单容器内存上限（platform-refinements 8.2/8.5）
async function handleMentorAllocate(): Promise<void> {
  if (!mentorAllocForm.studentId || !mentorAllocForm.instanceIds.length) {
    message.warning('请选择学生与物理实例')
    return
  }
  try {
    for (const instanceId of mentorAllocForm.instanceIds) {
      await allocationApi.allocate({
        instanceId,
        studentId: mentorAllocForm.studentId,
        groupId: myGroupId.value,
        perContainerMemoryMb: mentorAllocForm.perContainerMemoryMb,
      })
    }
    message.success(`已为该学生分配 ${mentorAllocForm.instanceIds.length} 台物理实例`)
    mentorAllocForm.instanceIds = []
    mentorAllocForm.perContainerMemoryMb = undefined
    load()
  } catch {
    // 拦截器已提示
  }
}

// 管理员按课题组分配物理实例（platform-refinements #2：多选，逐台分配给课题组）
async function handleAdminAllocate(): Promise<void> {
  if (!adminAllocForm.groupId || !adminAllocForm.instanceIds.length) {
    message.warning('请选择课题组与物理实例')
    return
  }
  try {
    for (const instanceId of adminAllocForm.instanceIds) {
      await allocationApi.allocateGroup({
        instanceId,
        groupId: adminAllocForm.groupId,
      })
    }
    message.success(`已为课题组分配 ${adminAllocForm.instanceIds.length} 台物理实例`)
    adminAllocForm.groupId = undefined
    adminAllocForm.instanceIds = []
    load()
  } catch {
    // 拦截器已提示
  }
}

const instanceLabel = (id: number) => {
  const i = instances.value.find((x) => x.id === id)
  return i ? `${i.instanceNumber} - ${i.machineName || '未命名'}` : String(id)
}

// 撤销分配（platform-refinements #3：二次确认 + 运行容器/存储池影响提示）
async function confirmRevokeAllocation(record: MachineAllocation): Promise<void> {
  let detail = ''
  try {
    const imp = await allocationApi.impact(record.id)
    const parts: string[] = [`学生：${imp.studentName}`]
    if (imp.runningContainers.length) {
      parts.push(`运行中容器 ${imp.runningContainers.length} 个：${imp.runningContainers.map((c) => c.name).join('、')}`)
    }
    if (imp.storagePools.length) {
      parts.push(`存储池 ${imp.storagePools.length} 个：${imp.storagePools.map((p) => p.poolName).join('、')}`)
    }
    detail = parts.join('\n')
  } catch {
    detail = '无法获取影响详情，是否仍要撤销？'
  }
  Modal.confirm({
    title: '确认撤销该分配？',
    content: detail + (detail.includes('运行中容器') || detail.includes('存储池') ? '\n\n该学生在此实例上仍有运行容器或存储池，撤销后其将无法在此实例创建新容器（已运行的容器与存储池不受影响）。' : ''),
    onOk: async () => {
      await allocationApi.deallocate(record.id)
      message.success('已撤销分配')
      load()
    },
  })
}
</script>

<template>
  <div>
    <a-typography-title :level="3">{{ pageTitle }}</a-typography-title>

    <!-- 导师：注册链接 -->
    <a-card v-if="!isAdmin" title="注册链接" style="margin-bottom: 24px">
      <template #extra>
        <a-button type="primary" @click="linkModalVisible = true">创建链接</a-button>
      </template>
      <a-table :data-source="links" row-key="id" :pagination="false">
        <a-table-column title="链接" :width="200">
          <template #default="{ record }">
            <a-typography-text ellipsis style="max-width: 180px; display: inline-block">
              {{ registerUrl(record.token) }}
            </a-typography-text>
          </template>
        </a-table-column>
        <a-table-column title="剩余次数" data-index="remainingCount" :width="100" />
        <a-table-column title="过期时间" :width="180">
          <template #default="{ record }">{{ dayjs(record.expireAt).format('YYYY-MM-DD HH:mm') }}</template>
        </a-table-column>
        <a-table-column title="状态" :width="80">
          <template #default="{ record }">
            <a-tag :color="statusColor[record.status]">{{ statusLabel[record.status] }}</a-tag>
          </template>
        </a-table-column>
        <a-table-column title="操作" :width="160">
          <template #default="{ record }">
            <a-button type="link" size="small" @click="copyLink(record.token)">复制链接</a-button>
            <a-button
              v-if="record.status === 'ACTIVE'" type="link" danger size="small"
              @click="confirmRevoke(record.token)"
            >作废</a-button>
          </template>
        </a-table-column>
      </a-table>
    </a-card>

    <!-- 导师：为学生分配物理实例（多选 + 单容器内存上限） -->
    <a-card v-if="!isAdmin" title="为学生分配物理实例" style="margin-bottom: 24px">
      <a-form layout="vertical">
        <a-row :gutter="16">
          <a-col :span="8">
            <a-form-item label="学生">
              <a-select v-model:value="mentorAllocForm.studentId" placeholder="选择学生">
                <a-select-option v-for="s in students" :key="s.id" :value="s.id">
                  {{ s.realName }}（{{ s.studentId || s.username }}）
                </a-select-option>
              </a-select>
            </a-form-item>
          </a-col>
          <a-col :span="10">
            <a-form-item label="物理实例（可多选）">
              <a-select
                v-model:value="mentorAllocForm.instanceIds"
                mode="multiple"
                placeholder="选择物理实例（可多选）"
              >
                <a-select-option v-for="i in instances" :key="i.id" :value="i.id">
                  {{ i.instanceNumber }} - {{ i.machineName || '未命名' }}
                </a-select-option>
              </a-select>
            </a-form-item>
          </a-col>
          <a-col :span="6">
            <a-form-item label="单容器内存上限（MB，可空=不限）">
              <a-input-number
                v-model:value="mentorAllocForm.perContainerMemoryMb"
                :min="512"
                placeholder="不限"
                style="width: 100%"
              />
            </a-form-item>
          </a-col>
        </a-row>
        <a-button type="primary" @click="handleMentorAllocate">分配</a-button>
      </a-form>
    </a-card>

    <!-- 导师：已分配机器 -->
    <a-card v-if="!isAdmin" title="已分配机器">
      <a-table :data-source="allocations" row-key="id" :pagination="false">
        <a-table-column title="学生" :width="120">
          <template #default="{ record }">{{ record.studentName ?? '-' }}</template>
        </a-table-column>
        <a-table-column title="实例" :width="160">
          <template #default="{ record }">{{ instanceLabel(record.instanceId) }}</template>
        </a-table-column>
        <a-table-column title="单容器内存上限" :width="140">
          <template #default="{ record }">
            <span v-if="record.perContainerMemoryMb">{{ record.perContainerMemoryMb }} MB</span>
            <span v-else style="color: #ccc">不限</span>
          </template>
        </a-table-column>
        <a-table-column title="分配时间" :width="180">
          <template #default="{ record }">{{ dayjs(record.allocatedAt).format('YYYY-MM-DD HH:mm') }}</template>
        </a-table-column>
        <a-table-column title="操作" :width="100">
          <template #default="{ record }">
            <a-button type="link" danger size="small" @click="confirmRevokeAllocation(record)">
              撤销
            </a-button>
          </template>
        </a-table-column>
      </a-table>
    </a-card>

    <!-- 管理员：按课题组分配物理实例 -->
    <a-card v-if="isAdmin" title="为课题组分配物理实例">
      <a-form layout="vertical">
        <a-row :gutter="16">
          <a-col :span="10">
            <a-form-item label="课题组">
              <a-select v-model:value="adminAllocForm.groupId" placeholder="选择课题组">
                <a-select-option v-for="g in groups" :key="g.id" :value="g.id">{{ g.name }}</a-select-option>
              </a-select>
            </a-form-item>
          </a-col>
          <a-col :span="10">
            <a-form-item label="物理实例（可多选）">
              <a-select
                v-model:value="adminAllocForm.instanceIds"
                mode="multiple"
                placeholder="选择物理实例（可多选）"
              >
                <a-select-option v-for="i in instances" :key="i.id" :value="i.id">
                  {{ i.instanceNumber }} - {{ i.machineName || '未命名' }}
                </a-select-option>
              </a-select>
            </a-form-item>
          </a-col>
          <a-col :span="4">
            <a-form-item label=" ">
              <a-button type="primary" style="width: 100%" @click="handleAdminAllocate">分配</a-button>
            </a-form-item>
          </a-col>
        </a-row>
      </a-form>
      <a-alert type="info" show-icon message="分配后该课题组全体成员均可在该物理实例上创建容器。" />
    </a-card>

    <!-- 课题组已分配实例（管理员看全部，导师看本组；platform-refinements #4/#7） -->
    <a-card :title="isAdmin ? '已分配资源' : '本课题组已分配实例'" style="margin-top: 24px">
      <a-table
        :data-source="groupAllocations"
        :row-key="(record: any) => record.groupName + '-' + record.instanceId"
        :pagination="false"
      >
        <a-table-column v-if="isAdmin" title="课题组" data-index="groupName" />
        <a-table-column title="物理实例" :width="120">
          <template #default="{ record }">{{ record.instanceNumber }}</template>
        </a-table-column>
        <a-table-column title="机器名" data-index="machineName" />
        <a-table-column title="分配时间" :width="180">
          <template #default="{ record }">{{ dayjs(record.allocatedAt).format('YYYY-MM-DD HH:mm') }}</template>
        </a-table-column>
      </a-table>
    </a-card>

    <a-modal v-model:open="linkModalVisible" title="创建注册链接" @ok="createLink">
      <a-form layout="vertical">
        <a-form-item label="可用注册次数">
          <a-input-number v-model:value="linkForm.remainingCount" :min="1" style="width: 100%" />
        </a-form-item>
        <a-form-item label="过期时间">
          <a-date-picker
            v-model:value="linkForm.expireAt"
            show-time
            format="YYYY-MM-DD HH:mm"
            style="width: 100%"
          />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>
