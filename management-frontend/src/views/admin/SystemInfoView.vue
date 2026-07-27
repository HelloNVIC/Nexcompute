<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import type { UploadProps } from 'ant-design-vue'
import { http } from '@/utils/request'
import { useAuthStore } from '@/stores/auth'
import { emailApi, type SmtpConfig, type TriggerSwitch, type BrandConfig, type LogoView } from '@/api/email'

const auth = useAuthStore()
const isAdmin = auth.role === 'ADMIN'
const loading = ref(false)
const saving = ref(false)
const form = reactive({ maintainer: '', maintainerPhone: '', owner: '', ownerPhone: '' })

// 邮箱配置
const smtp = reactive<SmtpConfig>({ from: '', host: '', port: '', protocol: 'smtps', user: '', passwd: '' })
const smtpLoading = ref(false)
const smtpSaving = ref(false)
const testTo = ref('')
const testSending = ref(false)

// 触发开关
const triggers = ref<TriggerSwitch[]>([])
const triggersLoading = ref(false)
const triggersSaving = ref(false)

// 品牌
const brand = reactive<BrandConfig>({ name: '', signature: '' })
const brandLoading = ref(false)
const brandSaving = ref(false)
const logo = ref<LogoView | null>(null)
const logoUploading = ref(false)

onMounted(async () => {
  loading.value = true
  try {
    const info = await http.get<Record<string, string>>('/system-info')
    form.maintainer = info.maintainer || ''
    form.maintainerPhone = info.maintainerPhone || ''
    form.owner = info.owner || ''
    form.ownerPhone = info.ownerPhone || ''
  } finally {
    loading.value = false
  }
  if (isAdmin) {
    loadEmail()
  }
})

async function loadEmail(): Promise<void> {
  smtpLoading.value = true
  triggersLoading.value = true
  brandLoading.value = true
  try {
    const [s, t, b, l] = await Promise.all([
      emailApi.getSmtp(),
      emailApi.getTriggers(),
      emailApi.getBrand(),
      emailApi.getLogo(),
    ])
    Object.assign(smtp, s)
    triggers.value = Object.values(t)
    Object.assign(brand, b)
    logo.value = l
  } catch {
    // 拦截器已提示
  } finally {
    smtpLoading.value = false
    triggersLoading.value = false
    brandLoading.value = false
  }
}

async function save(): Promise<void> {
  saving.value = true
  try {
    await http.put('/system-info', form)
    message.success('系统信息已保存')
  } catch {
    // 拦截器已提示
  } finally {
    saving.value = false
  }
}

async function saveSmtp(): Promise<void> {
  smtpSaving.value = true
  try {
    const updated = await emailApi.updateSmtp({ ...smtp })
    Object.assign(smtp, updated)
    message.success('SMTP 配置已保存')
  } catch {
    // 拦截器已提示
  } finally {
    smtpSaving.value = false
  }
}

async function sendTest(): Promise<void> {
  if (!testTo.value.trim()) {
    message.warning('请输入测试收件邮箱')
    return
  }
  testSending.value = true
  try {
    const res = await emailApi.sendTest(testTo.value.trim())
    message.success(res.message)
  } catch {
    // 拦截器已提示
  } finally {
    testSending.value = false
  }
}

async function toggleTrigger(key: string, checked: boolean): Promise<void> {
  triggersSaving.value = true
  try {
    const map: Record<string, boolean> = { [key]: checked }
    const result = await emailApi.updateTriggers(map)
    triggers.value = Object.values(result)
    message.success('触发开关已更新')
  } catch {
    // 拦截器已提示，回滚 UI 由重新加载恢复
    await emailApi.getTriggers().then((t) => (triggers.value = Object.values(t)))
  } finally {
    triggersSaving.value = false
  }
}

async function saveBrand(): Promise<void> {
  brandSaving.value = true
  try {
    const updated = await emailApi.updateBrand({ ...brand })
    Object.assign(brand, updated)
    message.success('品牌配置已保存')
  } catch {
    // 拦截器已提示
  } finally {
    brandSaving.value = false
  }
}

const beforeLogoUpload: UploadProps['beforeUpload'] = (file) => {
  const isImage = ['image/png', 'image/jpeg'].includes(file.type)
  if (!isImage) {
    message.error('Logo 仅支持 PNG/JPG 格式（不支持 SVG）')
    return false
  }
  const underLimit = file.size / 1024 / 1024 < 1
  if (!underLimit) {
    message.error('Logo 文件不得超过 1MB')
    return false
  }
  // 自定义上传
  uploadLogo(file)
  return false
}

async function uploadLogo(file: File): Promise<void> {
  logoUploading.value = true
  try {
    const res = await emailApi.uploadLogo(file)
    logo.value = res
    message.success('Logo 已上传')
  } catch {
    // 拦截器已提示
  } finally {
    logoUploading.value = false
  }
}
</script>

<template>
  <div class="system-info-view">
    <a-typography-title :level="3">系统信息</a-typography-title>

    <div class="cards-grid" :class="{ single: !isAdmin }">
      <a-card title="维护信息" :loading="loading">
        <a-form layout="vertical">
          <a-form-item label="维护人">
            <a-input v-model:value="form.maintainer" :disabled="!isAdmin" placeholder="维护人姓名" />
          </a-form-item>
          <a-form-item label="维护电话">
            <a-input v-model:value="form.maintainerPhone" :disabled="!isAdmin" placeholder="维护人联系电话" />
          </a-form-item>
          <a-form-item label="责任人">
            <a-input v-model:value="form.owner" :disabled="!isAdmin" placeholder="责任人姓名" />
          </a-form-item>
          <a-form-item label="责任人电话">
            <a-input v-model:value="form.ownerPhone" :disabled="!isAdmin" placeholder="责任人联系电话" />
          </a-form-item>
          <a-form-item v-if="isAdmin">
            <a-button type="primary" :loading="saving" @click="save">保存</a-button>
          </a-form-item>
        </a-form>
      </a-card>

      <a-card v-if="isAdmin" title="邮箱配置" :loading="smtpLoading">
        <a-form layout="vertical">
          <a-form-item label="发件人（FROM）">
            <a-input v-model:value="smtp.from" placeholder="发件人邮箱" />
          </a-form-item>
          <a-form-item label="SMTP 服务器（HOST）">
            <a-input v-model:value="smtp.host" placeholder="如 smtp.exmail.qq.com" />
          </a-form-item>
          <a-form-item label="SMTP 端口（PORT）">
            <a-input v-model:value="smtp.port" placeholder="如 465" />
          </a-form-item>
          <a-form-item label="协议（PROTOCOL）">
            <a-select v-model:value="smtp.protocol">
              <a-select-option value="smtps">smtps（SSL）</a-select-option>
              <a-select-option value="smtp">smtp（STARTTLS）</a-select-option>
            </a-select>
          </a-form-item>
          <a-form-item label="认证用户名（USER）">
            <a-input v-model:value="smtp.user" placeholder="SMTP 认证用户名" />
          </a-form-item>
          <a-form-item label="认证密码（PASSWD）">
            <a-input-password v-model:value="smtp.passwd" placeholder="留空或 **** 表示不变" autocomplete="off" />
          </a-form-item>
          <a-space>
            <a-button type="primary" :loading="smtpSaving" @click="saveSmtp">保存 SMTP 配置</a-button>
          </a-space>
        </a-form>
        <a-divider style="margin: 12px 0" />
        <a-form layout="inline">
          <a-form-item label="测试发送收件人">
            <a-input v-model:value="testTo" placeholder="输入收件邮箱" style="width: 240px" />
          </a-form-item>
          <a-form-item>
            <a-button :loading="testSending" @click="sendTest">发送测试邮件</a-button>
          </a-form-item>
        </a-form>
      </a-card>

      <a-card v-if="isAdmin" title="邮件触发时机" :loading="triggersLoading">
        <p style="color: #8c8c8c">关闭某类全局开关后，该类事件不再向任何用户发送邮件。</p>
        <a-list :data-source="triggers" :loading="triggersSaving" item-layout="horizontal">
          <template #renderItem="{ item }">
            <a-list-item>
              <a-list-item-meta :description="item.mandatory ? '强制提醒，用户不可关闭' : '用户可逐项关闭'">
                <template #title>{{ item.description }}</template>
              </a-list-item-meta>
              <template #actions>
                <a-switch
                  :checked="item.enabled"
                  :disabled="item.mandatory"
                  @change="(c: boolean) => toggleTrigger(item.triggerKey, c)"
                />
              </template>
            </a-list-item>
          </template>
        </a-list>
      </a-card>

      <a-card v-if="isAdmin" title="邮件品牌" :loading="brandLoading">
        <a-form layout="vertical">
          <a-form-item label="品牌名">
            <a-input v-model:value="brand.name" placeholder="如 合算 Nexcompute" />
          </a-form-item>
          <a-form-item label="邮件落款（支持多行）">
            <a-textarea v-model:value="brand.signature" :rows="3" placeholder="落款文本" />
          </a-form-item>
          <a-form-item>
            <a-button type="primary" :loading="brandSaving" @click="saveBrand">保存品牌配置</a-button>
          </a-form-item>
        </a-form>
        <a-divider style="margin: 12px 0" />
        <a-form layout="vertical">
          <a-form-item label="邮件 Logo（PNG/JPG，≤1MB）">
            <a-upload :show-upload-list="false" :before-upload="beforeLogoUpload" accept=".png,.jpg,.jpeg">
              <a-button :loading="logoUploading">上传 Logo</a-button>
            </a-upload>
          </a-form-item>
          <a-form-item v-if="logo && logo.dataUrl" label="当前 Logo 预览">
            <img :src="logo.dataUrl" alt="Logo" style="max-height: 60px; border: 1px solid #f0f0f0; padding: 4px" />
            <div style="color: #8c8c8c; font-size: 12px; margin-top: 4px">
              {{ logo.filename || '默认 Logo' }}
            </div>
          </a-form-item>
        </a-form>
      </a-card>
    </div>
  </div>
</template>

<style scoped>
.system-info-view {
  max-width: 1100px;
  margin: 0 auto;
}
/* 双排居中：管理员 4 卡片两列网格；窄屏堆叠为单列 */
.cards-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
  align-items: start;
}
/* 非管理员：仅维护信息卡，单列居中 */
.cards-grid.single {
  display: block;
  max-width: 640px;
  margin: 0 auto;
}
@media (max-width: 900px) {
  .cards-grid {
    grid-template-columns: 1fr;
  }
}
</style>
