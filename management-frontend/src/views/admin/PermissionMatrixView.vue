<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import { permissionApi, type PermissionMatrixItem, type Perm } from '@/api/permission'

const loading = ref(false)
const saving = ref(false)
const matrix = ref<PermissionMatrixItem[]>([])

// platform-refinements #5：用户信息必填项配置
const fieldConfig = reactive({
  realName: true,
  studentId: false,
  email: false,
  phone: false,
  groupId: false,
})
const fieldDefs: Array<{ key: keyof typeof fieldConfig; label: string }> = [
  { key: 'realName', label: '姓名' },
  { key: 'studentId', label: '工号/学号' },
  { key: 'email', label: '邮箱' },
  { key: 'phone', label: '手机号' },
  { key: 'groupId', label: '课题组' },
]
const fieldSaving = ref(false)

const roles = [
  { key: 'ADMIN', label: '管理员' },
  { key: 'MENTOR', label: '导师' },
  { key: 'STUDENT', label: '学生' },
]

onMounted(async () => {
  await load()
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
    matrix.value = await permissionApi.getMatrix()
  } catch {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

async function toggleField(key: keyof typeof fieldConfig, checked: boolean): Promise<void> {
  const prev = fieldConfig[key]
  fieldConfig[key] = checked
  fieldSaving.value = true
  try {
    await permissionApi.updateFieldConfig({ [key]: checked })
    message.success(`必填项已更新`)
  } catch {
    fieldConfig[key] = prev
  } finally {
    fieldSaving.value = false
  }
}

async function updatePerm(moduleCode: string, role: string, field: keyof Perm, checked: boolean): Promise<void> {
  const item = matrix.value.find((m) => m.moduleCode === moduleCode)
  if (!item) return
  const perm = item.permissions[role]
  if (!perm) {
    item.permissions[role] = { canView: false, canEdit: false, canDelete: false }
  }
  item.permissions[role][field] = checked

  saving.value = true
  try {
    const p = item.permissions[role]
    await permissionApi.update({
      role,
      moduleCode,
      canView: p.canView,
      canEdit: p.canEdit,
      canDelete: p.canDelete,
    })
    message.success(`${role} 对 ${item.moduleName} 权限已更新`)
  } catch {
    // 回滚
    item.permissions[role][field] = !checked
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div>
    <a-typography-title :level="3">权限矩阵配置</a-typography-title>
    <a-typography-paragraph type="secondary">
      角色 × 模块 × 操作（查看 / 编辑 / 删除）细粒度权限配置
    </a-typography-paragraph>

    <a-table :data-source="matrix" :loading="loading" :pagination="false" row-key="moduleCode" bordered>
      <a-table-column title="模块" data-index="moduleName" :width="150" />
      <a-table-column v-for="r in roles" :key="r.key" :title="r.label">
        <template #default="{ record }">
          <div style="display: flex; gap: 12px; flex-wrap: wrap">
            <a-checkbox
              :checked="record.permissions[r.key]?.canView"
              :disabled="r.key === 'ADMIN'"
              @change="(e: any) => updatePerm(record.moduleCode, r.key, 'canView', e.target.checked)"
            >查看</a-checkbox>
            <a-checkbox
              :checked="record.permissions[r.key]?.canEdit"
              :disabled="r.key === 'ADMIN'"
              @change="(e: any) => updatePerm(record.moduleCode, r.key, 'canEdit', e.target.checked)"
            >编辑</a-checkbox>
            <a-checkbox
              :checked="record.permissions[r.key]?.canDelete"
              :disabled="r.key === 'ADMIN'"
              @change="(e: any) => updatePerm(record.moduleCode, r.key, 'canDelete', e.target.checked)"
            >删除</a-checkbox>
          </div>
        </template>
      </a-table-column>
    </a-table>
    <a-alert v-if="saving" type="info" message="保存中..." style="margin-top: 16px" />

    <!-- 用户信息必填项（platform-refinements #5） -->
    <a-card title="用户信息必填项" style="margin-top: 24px">
      <a-space wrap>
        <a-checkbox
          v-for="f in fieldDefs"
          :key="f.key"
          :checked="fieldConfig[f.key]"
          @change="(e: any) => toggleField(f.key, e.target.checked)"
        >{{ f.label }}</a-checkbox>
      </a-space>
      <a-typography-text type="secondary" style="display: block; margin-top: 8px; font-size: 12px">
        勾选的字段在创建/编辑用户时为必填；用户名与角色始终必填。
      </a-typography-text>
      <a-alert v-if="fieldSaving" type="info" message="保存中..." style="margin-top: 8px" />
    </a-card>
  </div>
</template>
