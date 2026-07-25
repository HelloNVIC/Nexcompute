<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { message, Modal } from 'ant-design-vue'
import dayjs from 'dayjs'
import { groupApi, type ResearchGroup, type UserInfoDto } from '@/api/group'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const loading = ref(false)

// 导师/学生视图（我的课题组）
const group = ref<ResearchGroup | null>(null)
const students = ref<UserInfoDto[]>([])
const editing = ref(false)
const form = reactive({ name: '', description: '' })
const canEdit = computed(() => auth.role === 'MENTOR' || auth.role === 'ADMIN')

// 管理员视图（全量课题组管理）
const groups = ref<ResearchGroup[]>([])
const membersVisible = ref(false)
const membersGroup = ref<ResearchGroup | null>(null)
const members = ref<UserInfoDto[]>([])
const groupFormVisible = ref(false)
const groupForm = reactive({ id: undefined as number | undefined, name: '', description: '', mentorId: undefined as number | undefined })
const groupFormMode = ref<'create' | 'edit'>('create')

onMounted(load)

async function load(): Promise<void> {
  loading.value = true
  try {
    if (auth.role === 'ADMIN') {
      // 管理员：全量课题组列表（platform-improvements 任务 5.4）
      groups.value = await groupApi.list()
    } else {
      // 导师/学生：我的课题组
      group.value = await groupApi.my()
      form.name = group.value.name
      form.description = group.value.description ?? ''
      students.value = await groupApi.students(group.value.id)
    }
  } catch {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

async function handleSave(): Promise<void> {
  if (!group.value) return
  try {
    group.value = await groupApi.update(group.value.id, form)
    editing.value = false
    message.success('保存成功')
  } catch {
    // 拦截器已提示
  }
}

// 管理员：查看课题组成员
async function showMembers(g: ResearchGroup): Promise<void> {
  membersGroup.value = g
  membersVisible.value = true
  newMemberWorkerId.value = ''
  try {
    members.value = await groupApi.students(g.id)
  } catch {
    // 拦截器已提示
  }
}

// platform-refinements #6：成员增删
const newMemberWorkerId = ref('')

async function addMember(): Promise<void> {
  if (!membersGroup.value || !newMemberWorkerId.value.trim()) {
    message.warning('请输入工号')
    return
  }
  try {
    await groupApi.addMember(membersGroup.value.id, newMemberWorkerId.value.trim())
    message.success('成员已加入')
    newMemberWorkerId.value = ''
    members.value = await groupApi.students(membersGroup.value.id)
  } catch {
    // 拦截器已提示
  }
}

function confirmRemoveMember(m: UserInfoDto): void {
  if (!membersGroup.value) return
  Modal.confirm({
    title: `确认将「${m.realName}」移出课题组？`,
    content: '该学生转为"无课题组"，账号下的容器与存储池保留；登录时将被阻断。',
    onOk: async () => {
      await groupApi.removeMember(membersGroup.value!.id, m.id)
      message.success('已移出')
      members.value = await groupApi.students(membersGroup.value!.id)
    },
  })
}

function showCreateGroup(): void {
  groupFormMode.value = 'create'
  groupForm.id = undefined
  groupForm.name = ''
  groupForm.description = ''
  groupForm.mentorId = undefined
  groupFormVisible.value = true
}

function showEditGroup(g: ResearchGroup): void {
  groupFormMode.value = 'edit'
  groupForm.id = g.id
  groupForm.name = g.name
  groupForm.description = g.description ?? ''
  groupForm.mentorId = g.mentorId
  groupFormVisible.value = true
}

async function handleSaveGroup(): Promise<void> {
  if (!groupForm.name) {
    message.warning('请填写课题组名称')
    return
  }
  try {
    if (groupFormMode.value === 'create') {
      await groupApi.create({ name: groupForm.name, description: groupForm.description, mentorId: groupForm.mentorId })
      message.success('课题组已创建')
    } else if (groupForm.id) {
      await groupApi.update(groupForm.id, { name: groupForm.name, description: groupForm.description })
      message.success('课题组已更新')
    }
    groupFormVisible.value = false
    load()
  } catch {
    // 拦截器已提示
  }
}

function confirmDeleteGroup(g: ResearchGroup): void {
  Modal.confirm({
    title: `确认删除课题组「${g.name}」？`,
    content: '删除后成员关系解除，用户保留（group_id 置空）',
    onOk: async () => {
      await groupApi.remove(g.id)
      message.success('课题组已删除')
      load()
    },
  })
}
</script>

<template>
  <div v-if="loading"><a-spin /></div>

  <!-- 管理员：全量课题组管理 -->
  <div v-else-if="auth.role === 'ADMIN'">
    <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px">
      <a-typography-title :level="3" style="margin: 0">课题组信息（全量管理）</a-typography-title>
      <a-button type="primary" @click="showCreateGroup">新建课题组</a-button>
    </div>

    <a-table :data-source="groups" row-key="id" :pagination="false">
      <a-table-column title="课题组名称" data-index="name" :sorter="(a: ResearchGroup, b: ResearchGroup) => (a.name||'').localeCompare(b.name||'')" />
      <a-table-column title="描述" data-index="description">
        <template #default="{ record }">{{ record.description ?? '-' }}</template>
      </a-table-column>
      <a-table-column title="导师 ID" data-index="mentorId" :width="100" :sorter="(a: ResearchGroup, b: ResearchGroup) => (a.mentorId ?? 0) - (b.mentorId ?? 0)" />
      <a-table-column title="操作" :width="280">
        <template #default="{ record }">
          <a-button type="link" size="small" @click="showMembers(record)">查看成员</a-button>
          <a-button type="link" size="small" @click="showEditGroup(record)">编辑</a-button>
          <a-button type="link" danger size="small" @click="confirmDeleteGroup(record)">删除</a-button>
        </template>
      </a-table-column>
    </a-table>

    <!-- 新建/编辑课题组 -->
    <a-modal v-model:open="groupFormVisible" :title="groupFormMode === 'create' ? '新建课题组' : '编辑课题组'" @ok="handleSaveGroup">
      <a-form layout="vertical">
        <a-form-item label="课题组名称" required>
          <a-input v-model:value="groupForm.name" />
        </a-form-item>
        <a-form-item label="描述">
          <a-textarea v-model:value="groupForm.description" :rows="3" />
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- 课题组成员 -->
    <a-modal v-model:open="membersVisible" :title="`课题组成员 - ${membersGroup?.name ?? ''}`" :footer="null" width="700px">
      <!-- platform-refinements #6：按工号加成员 -->
      <div style="display: flex; gap: 8px; margin-bottom: 12px">
        <a-input v-model:value="newMemberWorkerId" placeholder="输入工号加入成员" @keyup.enter="addMember" />
        <a-button type="primary" @click="addMember">加入</a-button>
      </div>
      <a-table :data-source="members" row-key="id" :pagination="false">
        <a-table-column title="姓名" data-index="realName" />
        <a-table-column title="工号/学号" data-index="studentId" />
        <a-table-column title="邮箱" data-index="email" />
        <a-table-column title="手机号" data-index="phone" />
        <a-table-column title="状态" :width="80">
          <template #default="{ record }">
            <a-tag :color="record.status === 'ACTIVE' ? 'green' : 'red'">
              {{ record.status === 'ACTIVE' ? '正常' : '禁用' }}
            </a-tag>
          </template>
        </a-table-column>
        <a-table-column title="操作" :width="80">
          <template #default="{ record }">
            <a-button type="link" danger size="small" @click="confirmRemoveMember(record)">移出</a-button>
          </template>
        </a-table-column>
      </a-table>
    </a-modal>
  </div>

  <!-- 导师/学生：我的课题组 -->
  <div v-else-if="group">
    <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px">
      <a-typography-title :level="3" style="margin: 0">课题组信息</a-typography-title>
      <a-button v-if="canEdit && !editing" type="primary" @click="editing = true">编辑</a-button>
    </div>

    <a-descriptions v-if="!editing" :column="1" bordered>
      <a-descriptions-item label="课题组名称">{{ group.name }}</a-descriptions-item>
      <a-descriptions-item label="描述">{{ group.description ?? '-' }}</a-descriptions-item>
    </a-descriptions>

    <a-form v-else layout="vertical" style="max-width: 500px">
      <a-form-item label="课题组名称">
        <a-input v-model:value="form.name" />
      </a-form-item>
      <a-form-item label="描述">
        <a-textarea v-model:value="form.description" :rows="3" />
      </a-form-item>
      <a-space>
        <a-button type="primary" @click="handleSave">保存</a-button>
        <a-button @click="editing = false">取消</a-button>
      </a-space>
    </a-form>

    <a-typography-title :level="4" style="margin-top: 32px">课题组成员</a-typography-title>
    <a-table :data-source="students" row-key="id" :pagination="false">
      <a-table-column title="姓名" data-index="realName" />
      <a-table-column title="工号/学号" data-index="studentId" />
      <a-table-column title="邮箱" data-index="email" />
      <a-table-column title="手机号" data-index="phone" />
      <a-table-column title="状态" :width="80">
        <template #default="{ record }">
          <a-tag :color="record.status === 'ACTIVE' ? 'green' : 'red'">
            {{ record.status === 'ACTIVE' ? '正常' : '禁用' }}
          </a-tag>
        </template>
      </a-table-column>
    </a-table>
  </div>
  <a-empty v-else description="您还没有课题组" />
</template>
