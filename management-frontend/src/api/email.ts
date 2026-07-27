import { request } from '@/utils/request'

/** SMTP 配置（GET 脱敏，PASSWD 返回 ****；PUT 接受明文） */
export interface SmtpConfig {
  from: string
  host: string
  port: string
  protocol: string // smtps / smtp
  user: string
  passwd: string // GET 时为 ****，PUT 时传新明文或 **** 表示不变
}

/** 触发键全局开关项 */
export interface TriggerSwitch {
  triggerKey: string
  description: string
  mandatory: boolean
  enabled: boolean
}

/** 品牌名与落款 */
export interface BrandConfig {
  name: string
  signature: string
}

/** Logo 预览（data URL） */
export interface LogoView {
  filename: string
  contentType: string
  dataUrl: string
}

/** 用户偏好项 */
export interface EmailPref {
  triggerKey: string
  description: string
  mandatory: boolean
  enabled: boolean
}

export const emailApi = {
  // SMTP 配置
  getSmtp: () => request<SmtpConfig>({ method: 'GET', url: '/system-info/email' }),
  updateSmtp: (cfg: SmtpConfig) => request<SmtpConfig>({ method: 'PUT', url: '/system-info/email', data: cfg }),

  // 触发键全局开关
  getTriggers: () => request<Record<string, TriggerSwitch>>({ method: 'GET', url: '/system-info/email/triggers' }),
  updateTriggers: (map: Record<string, boolean>) =>
    request<Record<string, TriggerSwitch>>({ method: 'PUT', url: '/system-info/email/triggers', data: map }),

  // 品牌名 / 落款
  getBrand: () => request<BrandConfig>({ method: 'GET', url: '/system-info/email/brand' }),
  updateBrand: (cfg: BrandConfig) => request<BrandConfig>({ method: 'PUT', url: '/system-info/email/brand', data: cfg }),

  // Logo 上传 / 预览
  getLogo: () => request<LogoView>({ method: 'GET', url: '/system-info/email/logo' }),
  uploadLogo: (file: File, onProgress?: (percent: number) => void) => {
    const form = new FormData()
    form.append('file', file)
    return request<LogoView>({
      method: 'POST',
      url: '/system-info/email/logo',
      data: form,
      onUploadProgress: (e: { loaded: number; total?: number }) => {
        if (onProgress && e.total) onProgress(Math.round((e.loaded / e.total) * 100))
      },
    })
  },

  // 测试发送
  sendTest: (toEmail: string) =>
    request<{ message: string }>({ method: 'POST', url: '/system-info/email/test', data: { toEmail } }),

  // 用户偏好
  getPrefs: () => request<EmailPref[]>({ method: 'GET', url: '/me/email-prefs' }),
  updatePrefs: (map: Record<string, boolean>) =>
    request<EmailPref[]>({ method: 'PUT', url: '/me/email-prefs', data: map }),
}
