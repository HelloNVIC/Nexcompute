<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { message, Modal } from 'ant-design-vue'
import dayjs from 'dayjs'
import {
  nasAllocationApi,
  WEBUI_ROLE,
  type NasInvitation,
  type NasRegistrationListItem,
  type NasRegistrationDetail,
  type NasRegistrationStatus,
} from '@/api/nasAllocation'
import { copyToClipboard } from '@/utils/clipboard'

type ActionTarget = NasRegistrationListItem

const activeTab = ref<'invitations' | 'queue' | 'history' | 'provisioned'>('invitations')

// ---- 数据 ----
const invitations = ref<NasInvitation[]>([])
const pendingRegs = ref<NasRegistrationListItem[]>([])
const historyRegs = ref<NasRegistrationListItem[]>([])
const provisionedRegs = ref<NasRegistrationListItem[]>([])
const loadingInvitations = ref(false)
const loadingQueue = ref(false)
const loadingHistory = ref(false)
const loadingProvisioned = ref(false)

// ---- 邀请创建 ----
const createModalVisible = ref(false)
const creating = ref(false)
const createForm = reactive({ label: '', maxUses: 1, expireAt: dayjs().add(30, 'day') })

// ---- 详情 ----
const detailVisible = ref(false)
const detailLoading = ref(false)
const detailData = ref<NasRegistrationDetail | null>(null)

// ---- 批准 ----
const approveModalVisible = ref(false)
const approving = ref(false)
const approveTarget = ref<ActionTarget | null>(null)
const approveRole = ref<number | null>(null)

// ---- 拒绝 ----
const rejectModalVisible = ref(false)
const rejecting = ref(false)
const rejectTarget = ref<ActionTarget | null>(null)
const rejectReason = ref('')

// ---- 改角色 ----
const roleModalVisible = ref(false)
const roleSaving = ref(false)
const roleTarget = ref<ActionTarget | null>(null)
const roleValue = ref<number | null>(null)

// ---- 重申上游 ----
const reprovisionModalVisible = ref(false)
const reprovisioning = ref(false)
const reprovisionTarget = ref<ActionTarget | null>(null)
const reprovisionForm = reactive({ password: '', webuiGroupId: null as number | null })

// ---- 刷新状态 ----
const refreshing = ref(false)

// ---- 状态徽章 ----
const statusLabel: Record<NasRegistrationStatus, string> = {
  PENDING: '待审批', APPROVED: '已开通', REJECTED: '已拒绝', FAILED: '开通失败', NOT_FOUND: '用户已删',
}
const statusColor: Record<NasRegistrationStatus, string> = {
  PENDING: 'blue', APPROVED: 'green', REJECTED: 'red', FAILED: 'orange', NOT_FOUND: 'default',
}

// ---- 角色选项 ----
const roleOptions = [
  { label: '完全管理员（40）', value: WEBUI_ROLE.FULL_ADMIN },
  { label: '只读管理员（41）', value: WEBUI_ROLE.READONLY_ADMIN },
  { label: '共享管理员（42）', value: WEBUI_ROLE.SHARING_ADMIN },
  { label: '无（仅 SMB）', value: 0 }, // 0 表示无角色（提交时转 null）
]

onMounted(() => loadInvitations())

// ============ 加载 ============
async function loadInvitations(): Promise<void> {
  loadingInvitations.value = true
  try {
    invitations.value = await nasAllocationApi.listInvitations()
  } catch { /* 拦截器已提示 */ } finally {
    loadingInvitations.value = false
  }
}

async function loadQueue(): Promise<void> {
  loadingQueue.value = true
  try {
    pendingRegs.value = await nasAllocationApi.listRegistrations('PENDING')
  } catch { /* 拦截器已提示 */ } finally {
    loadingQueue.value = false
  }
}

async function loadHistory(): Promise<void> {
  loadingHistory.value = true
  try {
    // 审批历史 = 所有已处理（非 PENDING）记录，含通过/拒绝/失败/已删
    const all = await nasAllocationApi.listRegistrations()
    historyRegs.value = all.filter((r) => r.status !== 'PENDING')
  } catch { /* 拦截器已提示 */ } finally {
    loadingHistory.value = false
  }
}

async function loadProvisioned(): Promise<void> {
  loadingProvisioned.value = true
  try {
    // 已开通用户 tab 显示所有提过申请的用户（全状态）
    provisionedRegs.value = await nasAllocationApi.listRegistrations()
  } catch { /* 拦截器已提示 */ } finally {
    loadingProvisioned.value = false
  }
}

function onTabChange(key: string): void {
  if (key === 'invitations') loadInvitations()
  else if (key === 'queue') loadQueue()
  else if (key === 'history') loadHistory()
  else if (key === 'provisioned') loadProvisioned()
}

// ============ 邀请 ============
function openCreate(): void {
  createForm.label = ''
  createForm.maxUses = 1
  createForm.expireAt = dayjs().add(30, 'day')
  createModalVisible.value = true
}

async function doCreate(): Promise<void> {
  if (!createForm.label.trim()) { message.warning('请填写标签'); return }
  creating.value = true
  try {
    await nasAllocationApi.createInvitation({
      label: createForm.label.trim(),
      maxUses: createForm.maxUses,
      expireAt: createForm.expireAt.toISOString(),
    })
    message.success('邀请已创建')
    createModalVisible.value = false
    loadInvitations()
  } catch { /* 拦截器已提示 */ } finally {
    creating.value = false
  }
}

async function copyRegisterUrl(url: string): Promise<void> {
  const ok = await copyToClipboard(url)
  if (ok) message.success('注册链接已复制')
  else message.error('复制失败，请手动选中链接复制')
}

function confirmRevoke(inv: NasInvitation): void {
  Modal.confirm({
    title: '确认撤销此邀请？',
    content: '撤销后持有链接者将无法注册',
    onOk: async () => {
      await nasAllocationApi.revokeInvitation(inv.id)
      message.success('邀请已撤销')
      loadInvitations()
    },
  })
}

// ============ 详情 ============
async function openDetail(reg: NasRegistrationListItem): Promise<void> {
  detailVisible.value = true
  detailLoading.value = true
  detailData.value = null
  try {
    detailData.value = await nasAllocationApi.getRegistrationDetail(reg.id)
    // 详情可能翻转 NOT_FOUND，刷新已开通列表
    if (reg.status === 'APPROVED' || reg.status === 'NOT_FOUND') loadProvisioned()
  } catch { /* 拦截器已提示 */ } finally {
    detailLoading.value = false
  }
}

// ============ 批准 ============
function openApprove(reg: NasRegistrationListItem): void {
  approveTarget.value = reg
  approveRole.value = null
  approveModalVisible.value = true
}

async function doApprove(): Promise<void> {
  if (!approveTarget.value) return
  approving.value = true
  try {
    const role = approveRole.value === 0 ? null : approveRole.value
    const res = await nasAllocationApi.approve(approveTarget.value.id, role)
    message.success(res.message || '已开通')
    approveModalVisible.value = false
    reloadCurrent()
  } catch { /* 拦截器已提示 */ } finally {
    approving.value = false
  }
}

// ============ 拒绝 ============
function openReject(reg: NasRegistrationListItem): void {
  rejectTarget.value = reg
  rejectReason.value = ''
  rejectModalVisible.value = true
}

async function doReject(): Promise<void> {
  if (!rejectTarget.value) return
  rejecting.value = true
  try {
    await nasAllocationApi.reject(rejectTarget.value.id, rejectReason.value)
    message.success('已拒绝')
    rejectModalVisible.value = false
    reloadCurrent()
  } catch { /* 拦截器已提示 */ } finally {
    rejecting.value = false
  }
}

// ============ 改角色 ============
function openChangeRole(reg: NasRegistrationListItem): void {
  roleTarget.value = reg
  roleValue.value = null
  roleModalVisible.value = true
}

async function doChangeRole(): Promise<void> {
  if (!roleTarget.value) return
  roleSaving.value = true
  try {
    const role = roleValue.value === 0 ? null : roleValue.value
    const res = await nasAllocationApi.reapprove(roleTarget.value.id, role)
    message.success(res.message || '角色已更新')
    roleModalVisible.value = false
    reloadCurrent()
  } catch { /* 拦截器已提示 */ } finally {
    roleSaving.value = false
  }
}

// ============ 重申上游 ============
function openReprovision(reg: NasRegistrationListItem): void {
  reprovisionTarget.value = reg
  reprovisionForm.password = ''
  reprovisionForm.webuiGroupId = null
  reprovisionModalVisible.value = true
}

async function doReprovision(): Promise<void> {
  if (!reprovisionTarget.value) return
  if (reprovisionForm.password.length < 8) { message.warning('密码至少 8 位'); return }
  if (!/(?=.*[0-9])(?=.*[a-zA-Z])/.test(reprovisionForm.password)) { message.warning('密码需含字母和数字'); return }
  reprovisioning.value = true
  try {
    const role = reprovisionForm.webuiGroupId === 0 ? null : reprovisionForm.webuiGroupId
    const res = await nasAllocationApi.reprovision(
      reprovisionTarget.value.id, reprovisionForm.password, role)
    message.success(res.message || '已重新注册')
    reprovisionModalVisible.value = false
    reloadCurrent()
  } catch { /* 拦截器已提示 */ } finally {
    reprovisioning.value = false
  }
}

// ============ 刷新状态 ============
async function refreshStatuses(): Promise<void> {
  refreshing.value = true
  try {
    const res = await nasAllocationApi.refreshStatuses()
    message.success(`已检查 ${res.checked} 个，翻转 ${res.flipped} 个为 NOT_FOUND`)
    reloadCurrent()
  } catch { /* 拦截器已提示 */ } finally {
    refreshing.value = false
  }
}

// ============ 删除 ============
function confirmDelete(reg: NasRegistrationListItem, after: () => void): void {
  Modal.confirm({
    title: '确认删除此申请记录？',
    content: '仅删除本地记录，不删除 TrueNAS 用户',
    okType: 'danger',
    onOk: async () => {
      await nasAllocationApi.deleteRegistration(reg.id)
      message.success('记录已删除')
      after()
    },
  })
}

// ============ 辅助 ============
function fmt(s: string | null): string {
  return s ? dayjs(s).format('YYYY-MM-DD HH:mm') : '-'
}

/** 操作后按当前 tab 刷新 */
function reloadCurrent(): void {
  if (activeTab.value === 'queue') loadQueue()
  else if (activeTab.value === 'history') loadHistory()
  else if (activeTab.value === 'provisioned') loadProvisioned()
}

const detailTruenasEntries = computed<Array<[string, unknown]>>(() => {
  if (!detailData.value?.truenas) return []
  return Object.entries(detailData.value.truenas)
})
</script>

<template>
  <div>
    <a-typography-title :level="3" style="margin: 0 0 16px">NAS 分配</a-typography-title>

    <a-tabs v-model:activeKey="activeTab" @change="onTabChange">
      <!-- ============ 邀请管理 ============ -->
      <a-tab-pane key="invitations" tab="邀请管理">
        <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px">
          <a-typography-text type="secondary">创建邀请链接发送给被邀请人，凭链接注册 TrueNAS 用户</a-typography-text>
          <a-button type="primary" @click="openCreate">创建邀请</a-button>
        </div>

        <a-table :data-source="invitations" :loading="loadingInvitations" row-key="id" :pagination="{ pageSize: 20 }">
          <a-table-column title="标签" data-index="label" :width="140" />
          <a-table-column title="注册链接" :width="300">
            <template #default="{ record }">
              <a-typography-text :ellipsis="{ tooltip: record.registerUrl }" copyable style="font-size: 12px">
                {{ record.registerUrl }}
              </a-typography-text>
            </template>
          </a-table-column>
          <a-table-column title="名额" :width="90">
            <template #default="{ record }">{{ record.usedCount }}/{{ record.maxUses }}</template>
          </a-table-column>
          <a-table-column title="剩余" data-index="remaining" :width="70" />
          <a-table-column title="过期时间" :width="160">
            <template #default="{ record }">{{ fmt(record.expiresAt) }}</template>
          </a-table-column>
          <a-table-column title="状态" :width="90">
            <template #default="{ record }">
              <a-tag :color="record.valid ? 'green' : 'default'">{{ record.valid ? '有效' : (record.revokedAt ? '已撤销' : '已失效') }}</a-tag>
            </template>
          </a-table-column>
          <a-table-column title="创建时间" :width="160">
            <template #default="{ record }">{{ fmt(record.createdAt) }}</template>
          </a-table-column>
          <a-table-column title="操作" :width="180">
            <template #default="{ record }">
              <a-button type="link" size="small" @click="copyRegisterUrl(record.registerUrl)">复制链接</a-button>
              <a-button v-if="record.valid" type="link" danger size="small" @click="confirmRevoke(record)">撤销</a-button>
            </template>
          </a-table-column>
        </a-table>
      </a-tab-pane>

      <!-- ============ 审批队列 ============ -->
      <a-tab-pane key="queue" tab="审批队列">
        <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px">
          <a-typography-text type="secondary">待审批（PENDING）的注册申请，批准后经 TrueNAS REST 开通并通知用户</a-typography-text>
          <a-button @click="loadQueue">刷新</a-button>
        </div>

        <a-table :data-source="pendingRegs" :loading="loadingQueue" row-key="id" :pagination="{ pageSize: 20 }">
          <a-table-column title="用户名" data-index="username" :width="120" />
          <a-table-column title="姓名" data-index="fullName" :width="120" />
          <a-table-column title="邮箱" data-index="email" :width="200" />
          <a-table-column title="手机号" data-index="phone" :width="130" />
          <a-table-column title="提交时间" :width="160">
            <template #default="{ record }">{{ fmt(record.submittedAt) }}</template>
          </a-table-column>
          <a-table-column title="状态" :width="90">
            <template #default="{ record }">
              <a-tag :color="statusColor[record.status as NasRegistrationStatus]">{{ statusLabel[record.status as NasRegistrationStatus] }}</a-tag>
            </template>
          </a-table-column>
          <a-table-column title="操作" :width="260">
            <template #default="{ record }">
              <a-button type="link" size="small" @click="openApprove(record)">批准</a-button>
              <a-button type="link" danger size="small" @click="openReject(record)">拒绝</a-button>
              <a-button type="link" size="small" @click="openDetail(record)">详情</a-button>
              <a-button type="link" danger size="small" @click="confirmDelete(record, reloadCurrent)">删除</a-button>
            </template>
          </a-table-column>
        </a-table>
      </a-tab-pane>

      <!-- ============ 审批历史 ============ -->
      <a-tab-pane key="history" tab="审批历史">
        <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px">
          <a-typography-text type="secondary">所有已处理的审批记录（通过/拒绝/失败/已删），含审批人与审批时间</a-typography-text>
          <a-button @click="loadHistory">刷新</a-button>
        </div>

        <a-table :data-source="historyRegs" :loading="loadingHistory" row-key="id" :pagination="{ pageSize: 20 }">
          <a-table-column title="用户名" data-index="username" :width="120" />
          <a-table-column title="姓名" data-index="fullName" :width="110" />
          <a-table-column title="状态" :width="100">
            <template #default="{ record }">
              <a-tag :color="statusColor[record.status as NasRegistrationStatus]">{{ statusLabel[record.status as NasRegistrationStatus] }}</a-tag>
            </template>
          </a-table-column>
          <a-table-column title="TrueNAS ID" :width="100">
            <template #default="{ record }">{{ record.truenasUserId ?? '-' }}</template>
          </a-table-column>
          <a-table-column title="提交时间" :width="150">
            <template #default="{ record }">{{ fmt(record.submittedAt) }}</template>
          </a-table-column>
          <a-table-column title="审批时间" :width="150">
            <template #default="{ record }">{{ fmt(record.reviewedAt) }}</template>
          </a-table-column>
          <a-table-column title="备注" :width="220">
            <template #default="{ record }">
              <a-typography-text v-if="record.rejectReason" type="danger" :ellipsis="{ tooltip: record.rejectReason }" style="font-size: 12px">拒绝：{{ record.rejectReason }}</a-typography-text>
              <a-typography-text v-else-if="record.provisionError" type="warning" :ellipsis="{ tooltip: record.provisionError }" style="font-size: 12px">错误：{{ record.provisionError }}</a-typography-text>
              <span v-else>-</span>
            </template>
          </a-table-column>
          <a-table-column title="操作" :width="120">
            <template #default="{ record }">
              <a-button type="link" size="small" @click="openDetail(record)">详情</a-button>
              <a-button type="link" danger size="small" @click="confirmDelete(record, reloadCurrent)">删除</a-button>
            </template>
          </a-table-column>
        </a-table>
      </a-tab-pane>

      <!-- ============ 申请记录（已开通用户） ============ -->
      <a-tab-pane key="provisioned" tab="申请记录">
        <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px">
          <a-typography-text type="secondary">所有提过申请的用户（全状态）。APPROVED 可改角色 / 批量刷新状态，NOT_FOUND 可重申上游，PENDING 可批准/拒绝</a-typography-text>
          <a-button :loading="refreshing" @click="refreshStatuses">批量刷新状态</a-button>
        </div>

        <a-table :data-source="provisionedRegs" :loading="loadingProvisioned" row-key="id" :pagination="{ pageSize: 20 }">
          <a-table-column title="用户名" data-index="username" :width="120" />
          <a-table-column title="姓名" data-index="fullName" :width="110" />
          <a-table-column title="状态" :width="100">
            <template #default="{ record }">
              <a-tag :color="statusColor[record.status as NasRegistrationStatus]">{{ statusLabel[record.status as NasRegistrationStatus] }}</a-tag>
            </template>
          </a-table-column>
          <a-table-column title="TrueNAS ID" :width="100">
            <template #default="{ record }">{{ record.truenasUserId ?? '-' }}</template>
          </a-table-column>
          <a-table-column title="UID" :width="80">
            <template #default="{ record }">{{ record.truenasUid ?? '-' }}</template>
          </a-table-column>
          <a-table-column title="提交时间" :width="150">
            <template #default="{ record }">{{ fmt(record.submittedAt) }}</template>
          </a-table-column>
          <a-table-column title="操作" :width="300">
            <template #default="{ record }">
              <a-button v-if="record.status === 'PENDING'" type="link" size="small" @click="openApprove(record)">批准</a-button>
              <a-button v-if="record.status === 'PENDING'" type="link" danger size="small" @click="openReject(record)">拒绝</a-button>
              <a-button v-if="record.status === 'APPROVED'" type="link" size="small" @click="openChangeRole(record)">改角色</a-button>
              <a-button v-if="record.status === 'NOT_FOUND'" type="link" size="small" @click="openReprovision(record)">重申上游</a-button>
              <a-button type="link" size="small" @click="openDetail(record)">详情</a-button>
              <a-button type="link" danger size="small" @click="confirmDelete(record, reloadCurrent)">删除</a-button>
            </template>
          </a-table-column>
        </a-table>
      </a-tab-pane>
    </a-tabs>

    <!-- ============ 创建邀请 Modal ============ -->
    <a-modal v-model:open="createModalVisible" title="创建 NAS 邀请" :confirm-loading="creating" @ok="doCreate">
      <a-form layout="vertical">
        <a-form-item label="标签" required>
          <a-input v-model:value="createForm.label" placeholder="便于识别邀请用途" :maxlength="128" />
        </a-form-item>
        <a-form-item label="名额数" required>
          <a-input-number v-model:value="createForm.maxUses" :min="1" :max="100" style="width: 100%" />
        </a-form-item>
        <a-form-item label="过期时间" required>
          <a-date-picker v-model:value="createForm.expireAt" show-time format="YYYY-MM-DD HH:mm" style="width: 100%" />
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- ============ 详情 Modal ============ -->
    <a-modal v-model:open="detailVisible" title="注册申请详情" :footer="null" width="640px">
      <a-spin :spinning="detailLoading">
        <template v-if="detailData">
          <a-descriptions :column="2" size="small" bordered>
            <a-descriptions-item label="用户名">{{ detailData.registration.username }}</a-descriptions-item>
            <a-descriptions-item label="姓名">{{ detailData.registration.fullName }}</a-descriptions-item>
            <a-descriptions-item label="邮箱">{{ detailData.registration.email }}</a-descriptions-item>
            <a-descriptions-item label="手机号">{{ detailData.registration.phone }}</a-descriptions-item>
            <a-descriptions-item label="状态">
              <a-tag :color="statusColor[detailData.registration.status]">{{ statusLabel[detailData.registration.status] }}</a-tag>
            </a-descriptions-item>
            <a-descriptions-item label="TrueNAS ID">{{ detailData.registration.truenasUserId ?? '-' }}</a-descriptions-item>
            <a-descriptions-item label="UID">{{ detailData.registration.truenasUid ?? '-' }}</a-descriptions-item>
            <a-descriptions-item label="提交时间">{{ fmt(detailData.registration.submittedAt) }}</a-descriptions-item>
            <a-descriptions-item label="审核时间">{{ fmt(detailData.registration.reviewedAt) }}</a-descriptions-item>
            <a-descriptions-item label="暂存密码">{{ detailData.registration.hasPassword ? '是' : '否' }}</a-descriptions-item>
            <a-descriptions-item v-if="detailData.registration.rejectReason" label="拒绝原因" :span="2">
              {{ detailData.registration.rejectReason }}
            </a-descriptions-item>
            <a-descriptions-item v-if="detailData.registration.provisionError" label="开通错误" :span="2">
              <a-typography-text type="danger">{{ detailData.registration.provisionError }}</a-typography-text>
            </a-descriptions-item>
          </a-descriptions>

          <a-divider v-if="detailData.truenas" orientation="left" plain>TrueNAS 实时状态</a-divider>
          <a-descriptions v-if="detailData.truenas" :column="2" size="small" bordered>
            <a-descriptions-item v-for="[k, v] in detailTruenasEntries" :key="k" :label="k">{{ v }}</a-descriptions-item>
          </a-descriptions>
          <a-alert
            v-if="detailData.truenasLoginUrl"
            type="info" show-icon style="margin-top: 12px"
            message="TrueNAS 登录地址"
            :description="detailData.truenasLoginUrl"
          />
        </template>
      </a-spin>
    </a-modal>

    <!-- ============ 批准 Modal ============ -->
    <a-modal v-model:open="approveModalVisible" title="批准并开通" :confirm-loading="approving" @ok="doApprove">
      <a-typography-paragraph>
        将为用户 <b>{{ approveTarget?.username }}</b> 在 TrueNAS 创建用户并开通。请选择 Web 后台角色：
      </a-typography-paragraph>
      <a-select
        v-model:value="approveRole" placeholder="选择 Web 后台角色"
        :options="roleOptions" style="width: 100%"
      />
      <a-alert type="warning" show-icon style="margin-top: 12px" message="批准将解密暂存密码并经 TrueNAS REST 创建用户，成功后擦除密码。" />
    </a-modal>

    <!-- ============ 拒绝 Modal ============ -->
    <a-modal v-model:open="rejectModalVisible" title="拒绝申请" :confirm-loading="rejecting" @ok="doReject" ok-type="danger">
      <a-form layout="vertical">
        <a-form-item label="拒绝原因（选填）">
          <a-textarea v-model:value="rejectReason" :rows="3" :maxlength="255" placeholder="将记录原因，名额不退，用户名释放" />
        </a-form-item>
      </a-form>
    </a-modal>

    <!-- ============ 改角色 Modal ============ -->
    <a-modal v-model:open="roleModalVisible" title="改 Web 后台角色" :confirm-loading="roleSaving" @ok="doChangeRole">
      <a-typography-paragraph>更新用户 <b>{{ roleTarget?.username }}</b> 的 TrueNAS 附加组（保留 builtin_users）：</a-typography-paragraph>
      <a-select v-model:value="roleValue" placeholder="选择新角色" :options="roleOptions" style="width: 100%" />
    </a-modal>

    <!-- ============ 重申上游 Modal ============ -->
    <a-modal v-model:open="reprovisionModalVisible" title="重申上游（重建用户）" :confirm-loading="reprovisioning" @ok="doReprovision">
      <a-typography-paragraph>TrueNAS 上用户已删，用新密码重新创建用户 <b>{{ reprovisionTarget?.username }}</b>：</a-typography-paragraph>
      <a-form layout="vertical">
        <a-form-item label="新密码" required>
          <a-input-password v-model:value="reprovisionForm.password" placeholder="至少 8 位，含字母和数字" />
        </a-form-item>
        <a-form-item label="Web 后台角色">
          <a-select v-model:value="reprovisionForm.webuiGroupId" placeholder="选择角色" :options="roleOptions" style="width: 100%" />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>
