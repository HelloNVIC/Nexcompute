<script setup lang="ts">
// 审计开关（platform-audit-logging-ux D12）。
// 默认开；关闭二次确认（提示合规追溯丧失）；开关切换恒审计（后端 force=true）。
import { onMounted, ref } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { auditSwitchApi } from '@/api/auditSwitch'

const loading = ref(false)
const enabled = ref(true)

onMounted(async () => {
  loading.value = true
  try {
    const res = await auditSwitchApi.status()
    enabled.value = res.enabled
  } catch {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
})

function onChange(checked: boolean): void {
  if (checked) {
    // 开启：直接生效
    doToggle(true)
    return
  }
  // 关闭：二次确认
  Modal.confirm({
    title: '确认关闭审计？',
    content: '关闭后，新操作将不被记录，合规追溯性丧失。已存记录仍受保护不可删改。开关切换操作本身会被记录。',
    okText: '确认关闭',
    okType: 'danger',
    cancelText: '取消',
    onOk: () => doToggle(false),
    onCancel: () => {
      // 回退开关为开启
      enabled.value = true
    },
  })
}

async function doToggle(target: boolean): Promise<void> {
  loading.value = true
  try {
    const res = await auditSwitchApi.toggle(target)
    enabled.value = res.enabled
    message.success(target ? '审计已开启' : '审计已关闭')
  } catch {
    // 失败回退
    enabled.value = !target
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="audit-switch-view">
    <a-card title="审计开关" :bordered="false">
      <a-space direction="vertical" :size="16">
        <a-space>
          <span>审计功能：</span>
          <a-switch
            v-model:checked="enabled"
            :loading="loading"
            checked-children="开"
            un-checked-children="关"
            @change="onChange"
          />
          <a-tag :color="enabled ? 'green' : 'default'">{{ enabled ? '已开启' : '已关闭' }}</a-tag>
        </a-space>
        <a-alert
          type="info"
          show-icon
          message="说明"
          description="开启时记录所有非查询操作的审计日志；关闭后新操作不再记录，但已存记录受数据库触发器保护，不可修改或删除。开关切换操作恒被审计（即使审计已关闭），防止掩盖痕迹。"
        />
      </a-space>
    </a-card>
  </div>
</template>

<style scoped>
.audit-switch-view {
  padding: 16px;
  max-width: 720px;
  margin: 0 auto;
}
</style>
