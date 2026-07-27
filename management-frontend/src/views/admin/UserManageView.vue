<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { message, Modal } from 'ant-design-vue'
import dayjs from 'dayjs'
import { userApi, type CreateUserPayload } from '@/api/user'
import { groupApi, type ResearchGroup } from '@/api/group'
import { allocationApi } from '@/api/allocation'
import { permissionApi } from '@/api/permission'
import type { UserInfoDto, UserRole } from '@/types'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
// platform-refinements #5：用户必填项配置（管理员在权限矩阵页管理）
const fieldConfig = reactive({ realName: true, studentId: false, email: false, phone: false, groupId: false })
const loading = ref(false)
const users = ref<UserInfoDto[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const roleFilter = ref<UserRole | undefined>(undefined)

const modalVisible = ref(false)
const modalTitle = ref('创建用户')
const submitting = ref(false)
const editingUserId = ref<number | undefined>()

const form = reactive<CreateUserPayload & { status?: string }>({
  username: '',
  password: '',
  realName: '',
  role: 'STUDENT',
  studentId: '',
  email: '',
  phone: '',
  groupId: undefined,
})

// 课题组归属（platform-refinements 9.2：编辑用户时多选加入/移除）
const allGroups = ref<ResearchGroup[]>([])
const selectedGroupIds = ref<number[]>([])

// 重置密码（platform-refinements 9.2）
const resetVisible = ref(false)
const resetUserId = ref<number | undefined>()
const resetPassword = ref('')

const roleOptions = [
  { value: 'ADMIN', label: '管理员' },
  { value: 'MENTOR', label: '导师' },
  { value: 'STUDENT', label: '学生' },
]

const roleLabel: Record<string, string> = { ADMIN: '管理员', MENTOR: '导师', STUDENT: '学生' }
const roleColor: Record<string, string> = { ADMIN: 'red', MENTOR: 'orange', STUDENT: 'blue' }

// platform-audit-logging-ux 11.1：用户详情抽屉（基本信息/角色/课题组/资源分配）
const detailVisible = ref(false)
const detailUser = ref<UserInfoDto | null>(null)
const detailGroups = ref<ResearchGroup[]>([])
const detailAllocations = ref<Array<{ instanceNumber: string; machineName?: string; allocatedAt: string; groupName: string }>>([])

async function showDetail(user: UserInfoDto): Promise<void> {
  detailUser.value = user
  detailGroups.value = []
  detailAllocations.value = []
  detailVisible.value = true
  try {
    detailGroups.value = await userApi.userGroups(user.id)
    // 资源分配：取该用户所在课题组的机器分配（管理员/导师/学生通用展示）
    const all = await allocationApi.groupAllocations()
    const groupIds = new Set(detailGroups.value.map((g) => g.id))
    detailAllocations.value = all.filter((a) => groupIds.has(a.groupId))
  } catch {
    // 拦截器已提示
  }
}

onMounted(async () => {
  await load()
  // 课题组选项（管理员可见全部）
  try {
    allGroups.value = await groupApi.list()
  } catch {
    allGroups.value = []
  }
  // platform-refinements #5：加载必填项配置
  try {
    const cfg = await permissionApi.getFieldConfig()
    Object.assign(fieldConfig, cfg)
  } catch {
    // 静默
  }
})

async function load(): Promise<void> {
  loading.value = true
  try {
    const res = await userApi.list({ role: roleFilter.value, page: page.value - 1, size: size.value })
    users.value = res.content
    total.value = res.totalElements
  } catch {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

function showCreate(): void {
  modalTitle.value = '创建用户'
  editingUserId.value = undefined
  Object.assign(form, {
    username: '', password: '', realName: '', role: 'STUDENT',
    studentId: '', email: '', phone: '', groupId: undefined,
  })
  selectedGroupIds.value = []
  modalVisible.value = true
}

async function showEdit(user: UserInfoDto): Promise<void> {
  modalTitle.value = '编辑用户'
  editingUserId.value = user.id
  Object.assign(form, {
    username: user.username,
    password: '',
    realName: user.realName,
    role: user.role,
    studentId: user.studentId ?? '',
    email: user.email ?? '',
    phone: user.phone ?? '',
    groupId: user.groupId,
  })
  // 回显用户当前课题组归属
  try {
    const groups = await userApi.userGroups(user.id)
    selectedGroupIds.value = groups.map((g) => g.id)
  } catch {
    selectedGroupIds.value = user.groupId ? [user.groupId] : []
  }
  modalVisible.value = true
}

async function handleSubmit(): Promise<void> {
  // platform-refinements #5：按必填项配置前端校验
  if (!form.username) { message.warning('用户名为必填'); submitting.value = false; return }
  if (!form.realName) { message.warning('姓名为必填'); submitting.value = false; return }
  if (fieldConfig.studentId && !form.studentId) { message.warning('工号/学号为必填'); submitting.value = false; return }
  if (fieldConfig.email && !form.email) { message.warning('邮箱为必填'); submitting.value = false; return }
  if (fieldConfig.phone && !form.phone) { message.warning('手机号为必填'); submitting.value = false; return }
  submitting.value = true
  try {
    if (modalTitle.value === '创建用户') {
      await userApi.create(form)
      // 创建后若选了课题组，同步归属
      if (editingUserId.value && selectedGroupIds.value.length) {
        // create 未返回 id（返回 UserInfoDto），简化：创建后由编辑流程管理
      }
      message.success('用户创建成功')
    } else if (editingUserId.value) {
      const { password, ...rest } = form
      await userApi.update(editingUserId.value, password ? { ...rest, password } : rest)
      // 同步课题组归属（platform-refinements 9.2）
      await userApi.updateGroups(editingUserId.value, selectedGroupIds.value)
      message.success('用户更新成功')
    }
    modalVisible.value = false
    load()
  } catch {
    // 拦截器已提示
  } finally {
    submitting.value = false
  }
}

function showResetPassword(user: UserInfoDto): void {
  resetUserId.value = user.id
  resetPassword.value = ''
  resetVisible.value = true
}

async function handleResetPassword(): Promise<void> {
  if (!resetPassword.value) {
    message.warning('请输入新密码')
    return
  }
  if (!resetUserId.value) return
  try {
    await userApi.resetPassword(resetUserId.value, resetPassword.value)
    message.success('密码已重置，用户可用新密码登录')
    resetVisible.value = false
  } catch {
    // 拦截器已提示
  }
}

function confirmDisable(user: UserInfoDto): void {
  Modal.confirm({
    title: `确认禁用用户 ${user.realName}？`,
    content: '禁用后用户将无法登录',
    onOk: async () => {
      await userApi.disable(user.id)
      message.success('用户已禁用')
      load()
    },
  })
}

function confirmEnable(user: UserInfoDto): void {
  Modal.confirm({
    title: `确认启用用户 ${user.realName}？`,
    content: '启用后用户可恢复登录',
    onOk: async () => {
      await userApi.enable(user.id)
      message.success('用户已启用')
      load()
    },
  })
}

function confirmDelete(user: UserInfoDto): void {
  Modal.confirm({
    title: `确认删除用户 ${user.realName}？`,
    content: '删除后不可恢复；若该用户有运行中容器将被拒绝。其作为导师的课题组保留、成员关系与共享关系清除。',
    onOk: async () => {
      try {
        await userApi.remove(user.id)
        message.success('用户已删除')
        load()
      } catch {
        // 拦截器已提示
      }
    },
  })
}
</script>

<template>
  <div>
    <div style="display: flex; justify-content: space-between; margin-bottom: 16px">
      <a-typography-title :level="3" style="margin: 0">用户与课题组管理</a-typography-title>
      <a-space>
        <a-select
          v-model:value="roleFilter"
          placeholder="按角色筛选"
          style="width: 150px"
          allow-clear
          @change="load"
        >
          <a-select-option v-for="r in roleOptions" :key="r.value" :value="r.value">{{ r.label }}</a-select-option>
        </a-select>
        <a-button type="primary" @click="showCreate">创建用户</a-button>
      </a-space>
    </div>

    <a-table :data-source="users" :loading="loading" row-key="id" :pagination="{
      current: page, pageSize: size, total, showSizeChanger: true,
      onChange: (p: number) => { page = p; load() },
    }">
      <a-table-column title="用户名" data-index="username" :sorter="(a: UserInfoDto, b: UserInfoDto) => (a.username||'').localeCompare(b.username||'')" />
      <a-table-column title="姓名" data-index="realName" :sorter="(a: UserInfoDto, b: UserInfoDto) => (a.realName||'').localeCompare(b.realName||'')" />
      <a-table-column title="角色" :width="100" :sorter="(a: UserInfoDto, b: UserInfoDto) => (a.role||'').localeCompare(b.role||'')">
        <template #default="{ record }">
          <a-tag :color="roleColor[record.role]">{{ roleLabel[record.role] }}</a-tag>
        </template>
      </a-table-column>
      <a-table-column title="工号/学号" data-index="studentId" :sorter="(a: UserInfoDto, b: UserInfoDto) => (a.studentId||'').localeCompare(b.studentId||'')" />
      <a-table-column title="课题组" data-index="groupName" :sorter="(a: UserInfoDto, b: UserInfoDto) => (a.groupName||'').localeCompare(b.groupName||'')" />
      <a-table-column title="状态" :width="80" :sorter="(a: UserInfoDto, b: UserInfoDto) => (a.status||'').localeCompare(b.status||'')">
        <template #default="{ record }">
          <a-tag :color="record.status === 'ACTIVE' ? 'green' : 'red'">
            {{ record.status === 'ACTIVE' ? '正常' : '禁用' }}
          </a-tag>
        </template>
      </a-table-column>
      <a-table-column title="注册时间" :width="170" :sorter="(a: UserInfoDto, b: UserInfoDto) => (a.createdAt||'').localeCompare(b.createdAt||'')">
        <template #default="{ record }">{{ record.createdAt ? dayjs(record.createdAt).format('YYYY-MM-DD HH:mm') : '-' }}</template>
      </a-table-column>
      <a-table-column title="操作" :width="300">
        <template #default="{ record }">
          <a-button type="link" size="small" @click="showDetail(record)">详情</a-button>
          <a-button type="link" size="small" @click="showEdit(record)">编辑</a-button>
          <a-button type="link" size="small" @click="showResetPassword(record)">重置密码</a-button>
          <a-button
            v-if="record.id !== auth.user?.id && record.status === 'ACTIVE'"
            type="link" danger size="small" @click="confirmDisable(record)"
          >禁用</a-button>
          <a-button
            v-if="record.id !== auth.user?.id && record.status !== 'ACTIVE'"
            type="link" size="small" @click="confirmEnable(record)"
          >启用</a-button>
          <a-button
            v-if="record.id !== auth.user?.id"
            type="link" danger size="small" @click="confirmDelete(record)"
          >删除</a-button>
        </template>
      </a-table-column>
    </a-table>

    <a-modal v-model:open="modalVisible" :title="modalTitle" :confirm-loading="submitting" @ok="handleSubmit">
      <a-form layout="vertical">
        <a-form-item label="用户名">
          <a-input v-model:value="form.username" :disabled="modalTitle === '编辑用户'" />
        </a-form-item>
        <a-form-item :label="modalTitle === '创建用户' ? '密码' : '重置密码（留空不改）'">
          <a-input-password v-model:value="form.password" />
        </a-form-item>
        <a-form-item label="姓名" required>
          <a-input v-model:value="form.realName" />
        </a-form-item>
        <a-form-item label="角色">
          <a-select v-model:value="form.role">
            <a-select-option v-for="r in roleOptions" :key="r.value" :value="r.value">{{ r.label }}</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item label="工号/学号" :required="fieldConfig.studentId">
          <a-input v-model:value="form.studentId" />
        </a-form-item>
        <a-form-item label="邮箱" :required="fieldConfig.email">
          <a-input v-model:value="form.email" />
        </a-form-item>
        <a-form-item label="手机号" :required="fieldConfig.phone">
          <a-input v-model:value="form.phone" />
        </a-form-item>
        <!-- 课题组归属（platform-refinements 9.2：加入/移除） -->
        <a-form-item v-if="modalTitle === '编辑用户'" label="课题组归属">
          <a-select
            v-model:value="selectedGroupIds"
            mode="multiple"
            placeholder="选择课题组（可加入/移除）"
          >
            <a-select-option v-for="g in allGroups" :key="g.id" :value="g.id">{{ g.name }}</a-select-option>
          </a-select>
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- 重置密码（platform-refinements 9.2） -->
    <a-modal v-model:open="resetVisible" title="重置密码" @ok="handleResetPassword">
      <a-input-password v-model:value="resetPassword" placeholder="输入新密码" />
      <div style="font-size: 12px; color: #999; margin-top: 4px">重置后用户可用新密码登录。</div>
    </a-modal>

    <!-- platform-audit-logging-ux 11.1：用户详情抽屉 -->
    <a-drawer v-model:open="detailVisible" title="用户详情" width="640" :footer="null">
      <a-descriptions v-if="detailUser" :column="2" bordered size="small">
        <a-descriptions-item label="用户名">{{ detailUser.username }}</a-descriptions-item>
        <a-descriptions-item label="姓名">{{ detailUser.realName }}</a-descriptions-item>
        <a-descriptions-item label="角色">
          <a-tag :color="roleColor[detailUser.role]">{{ roleLabel[detailUser.role] }}</a-tag>
        </a-descriptions-item>
        <a-descriptions-item label="状态">
          <a-tag :color="detailUser.status === 'ACTIVE' ? 'green' : 'default'">
            {{ detailUser.status === 'ACTIVE' ? '正常' : '禁用' }}
          </a-tag>
        </a-descriptions-item>
        <a-descriptions-item label="工号/学号">{{ detailUser.studentId || '-' }}</a-descriptions-item>
        <a-descriptions-item label="邮箱">{{ detailUser.email || '-' }}</a-descriptions-item>
        <a-descriptions-item label="手机">{{ detailUser.phone || '-' }}</a-descriptions-item>
        <a-descriptions-item label="注册时间">
          {{ detailUser.createdAt ? dayjs(detailUser.createdAt).format('YYYY-MM-DD HH:mm') : '-' }}
        </a-descriptions-item>
      </a-descriptions>

      <a-divider orientation="left">所属课题组</a-divider>
      <a-empty v-if="!detailGroups.length" description="无课题组" />
      <a-list v-else size="small" :data-source="detailGroups">
        <template #renderItem="{ item }">
          <a-list-item>
            <a-list-item-meta :title="item.name" :description="item.description || '课题组'" />
          </a-list-item>
        </template>
      </a-list>

      <a-divider orientation="left">资源分配</a-divider>
      <a-empty v-if="!detailAllocations.length" description="无机器分配" />
      <a-list v-else size="small" :data-source="detailAllocations">
        <template #renderItem="{ item }">
          <a-list-item>
            <a-list-item-meta
              :title="`实例 ${item.instanceNumber}`"
              :description="`${item.groupName}${item.machineName ? ' · ' + item.machineName : ''} · 分配于 ${dayjs(item.allocatedAt).format('YYYY-MM-DD HH:mm')}`"
            />
          </a-list-item>
        </template>
      </a-list>
    </a-drawer>
  </div>
</template>
