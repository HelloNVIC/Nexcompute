<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { message, Modal } from 'ant-design-vue'
import dayjs from 'dayjs'
import { adminInviteApi, type AdminRegistrationLink } from '@/api/adminInvite'

const links = ref<AdminRegistrationLink[]>([])
const loading = ref(false)
const creating = ref(false)

const linkModalVisible = ref(false)
const linkForm = reactive({ remainingCount: 1, expireAt: dayjs().add(30, 'day') })

const statusLabel: Record<string, string> = {
  ACTIVE: '有效', REVOKED: '已作废', EXHAUSTED: '已耗尽', EXPIRED: '已过期',
}
const statusColor: Record<string, string> = {
  ACTIVE: 'green', REVOKED: 'red', EXHAUSTED: 'orange', EXPIRED: 'default',
}

onMounted(load)

async function load(): Promise<void> {
  loading.value = true
  try {
    links.value = await adminInviteApi.list()
  } catch {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

async function createLink(): Promise<void> {
  creating.value = true
  try {
    await adminInviteApi.create({
      remainingCount: linkForm.remainingCount,
      expireAt: linkForm.expireAt.toISOString(),
    })
    message.success('管理员邀请链接已创建')
    linkModalVisible.value = false
    load()
  } catch {
    // 拦截器已提示
  } finally {
    creating.value = false
  }
}

function copyLink(token: string): void {
  navigator.clipboard.writeText(adminInviteApi.registerUrl(token))
  message.success('邀请链接已复制')
}

function confirmRevoke(token: string): void {
  Modal.confirm({
    title: '确认作废此管理员邀请链接？',
    content: '作废后持有链接者将无法注册为管理员',
    onOk: async () => {
      await adminInviteApi.revoke(token)
      message.success('链接已作废')
      load()
    },
  })
}
</script>

<template>
  <div>
    <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px">
      <a-typography-title :level="3" style="margin: 0">管理员注册邀请</a-typography-title>
      <a-button type="primary" @click="linkModalVisible = true">创建管理员邀请链接</a-button>
    </div>

    <a-typography-paragraph type="secondary">
      创建邀请链接后发送给被邀请人；被邀请人打开链接填写个人信息（与学生注册表单一致：姓名/工号/密码/邮箱/手机）完成注册，注册后即成为管理员。
    </a-typography-paragraph>

    <a-table :data-source="links" :loading="loading" row-key="id" :pagination="{ pageSize: 20 }">
      <a-table-column title="邀请链接" :width="280">
        <template #default="{ record }">
          <a-typography-text :ellipsis="{ tooltip: adminInviteApi.registerUrl(record.token) }" copyable style="font-size: 12px">
            {{ adminInviteApi.registerUrl(record.token) }}
          </a-typography-text>
        </template>
      </a-table-column>
      <a-table-column title="剩余次数" :width="100" data-index="remainingCount" />
      <a-table-column title="过期时间" :width="170">
        <template #default="{ record }">{{ dayjs(record.expireAt).format('YYYY-MM-DD HH:mm') }}</template>
      </a-table-column>
      <a-table-column title="状态" :width="90">
        <template #default="{ record }">
          <a-tag :color="statusColor[record.status] || 'default'">{{ statusLabel[record.status] || record.status }}</a-tag>
        </template>
      </a-table-column>
      <a-table-column title="创建时间" :width="170">
        <template #default="{ record }">{{ dayjs(record.createdAt).format('YYYY-MM-DD HH:mm') }}</template>
      </a-table-column>
      <a-table-column title="操作" :width="200">
        <template #default="{ record }">
          <a-button type="link" size="small" @click="copyLink(record.token)">复制链接</a-button>
          <a-button
            v-if="record.status === 'ACTIVE'"
            type="link" danger size="small" @click="confirmRevoke(record.token)"
          >作废</a-button>
        </template>
      </a-table-column>
    </a-table>

    <a-modal v-model:open="linkModalVisible" title="创建管理员邀请链接" :confirm-loading="creating" @ok="createLink">
      <a-form layout="vertical">
        <a-form-item label="可注册次数">
          <a-input-number v-model:value="linkForm.remainingCount" :min="1" :max="100" style="width: 100%" />
        </a-form-item>
        <a-form-item label="过期时间">
          <a-date-picker
            v-model:value="linkForm.expireAt"
            show-time
            format="YYYY-MM-DD HH:mm"
            style="width: 100%"
          />
        </a-form-item>
        <a-alert
          type="warning"
          show-icon
          message="被邀请人凭此链接注册即为管理员，拥有系统全部权限，请谨慎发放。"
        />
      </a-form>
    </a-modal>
  </div>
</template>
