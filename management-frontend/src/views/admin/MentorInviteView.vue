<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { message, Modal } from 'ant-design-vue'
import dayjs from 'dayjs'
import { mentorInviteApi, type MentorRegistrationLink } from '@/api/mentorInvite'

const links = ref<MentorRegistrationLink[]>([])
const loading = ref(false)
const creating = ref(false)

const linkModalVisible = ref(false)
const linkForm = reactive({ remainingCount: 5, expireAt: dayjs().add(30, 'day') })

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
    links.value = await mentorInviteApi.list()
  } catch {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

async function createLink(): Promise<void> {
  creating.value = true
  try {
    await mentorInviteApi.create({
      remainingCount: linkForm.remainingCount,
      expireAt: linkForm.expireAt.toISOString(),
    })
    message.success('导师邀请链接已创建')
    linkModalVisible.value = false
    load()
  } catch {
    // 拦截器已提示
  } finally {
    creating.value = false
  }
}

function copyLink(token: string): void {
  navigator.clipboard.writeText(mentorInviteApi.registerUrl(token))
  message.success('邀请链接已复制')
}

function confirmRevoke(token: string): void {
  Modal.confirm({
    title: '确认作废此导师邀请链接？',
    content: '作废后持有链接者将无法注册',
    onOk: async () => {
      await mentorInviteApi.revoke(token)
      message.success('链接已作废')
      load()
    },
  })
}
</script>

<template>
  <div>
    <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px">
      <a-typography-title :level="3" style="margin: 0">导师邀请注册</a-typography-title>
      <a-button type="primary" @click="linkModalVisible = true">创建导师邀请链接</a-button>
    </div>

    <a-typography-paragraph type="secondary">
      创建邀请链接后发送给导师；导师打开链接填写个人信息与课题组信息完成注册，注册后自动建立课题组并设为该导师。
    </a-typography-paragraph>

    <a-table :data-source="links" :loading="loading" row-key="id" :pagination="{ pageSize: 20 }">
      <a-table-column title="邀请链接" :width="280">
        <template #default="{ record }">
          <a-typography-text :ellipsis="{ tooltip: mentorInviteApi.registerUrl(record.token) }" copyable style="font-size: 12px">
            {{ mentorInviteApi.registerUrl(record.token) }}
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

    <a-modal v-model:open="linkModalVisible" title="创建导师邀请链接" :confirm-loading="creating" @ok="createLink">
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
      </a-form>
    </a-modal>
  </div>
</template>
