import { http } from '@/utils/request'

export type RegisterLinkType = 'STUDENT' | 'MENTOR' | 'ADMIN'

export interface RegisterLinkValidateResult {
  valid: boolean
  linkType: string
  /** 失效原因（valid=false 时给出：作废/用尽/过期/不存在/类型不匹配） */
  reason?: string
}

/** 工号/学号可用性校验结果（GET /auth/register/check-username） */
export interface UsernameAvailabilityResult {
  available: boolean
  /** 失效原因（仅邀请链接无效时为「邀请链接无效」，不反馈存在性） */
  reason?: string
}

/** 忘记密码发送验证码响应（统一成功消息，防账号枚举） */
export interface PasswordResetSentResult {
  message: string
}

export const authApi = {
  /**
   * 校验注册链接有效性（不消耗名额）。
   * 学生 / 导师 / 管理员邀请链接共用此端点，凭 linkType 区分。
   * 失效时 valid=false 且 reason 给出具体原因，供注册页直接显示失效提示。
   */
  validateRegisterLink: (token: string, linkType: RegisterLinkType) =>
    http.get<RegisterLinkValidateResult>('/auth/register/validate', { token, linkType }),

  /** 用户自助修改密码（已认证）：校验旧密码 + 新密码强度 + 新旧不同，BCrypt 重新哈希落库 */
  changePassword: (oldPassword: string, newPassword: string) =>
    http.post<void>('/auth/change-password', { oldPassword, newPassword }),

  /** 忘记密码-发送验证码（公开）：无论账号是否存在均返回统一消息（防枚举） */
  sendResetCode: (username: string) =>
    http.post<PasswordResetSentResult>('/auth/forgot-password/send-code', { username }),

  /** 忘记密码-重置（公开）：凭验证码 + 新密码完成重置 */
  resetPassword: (username: string, code: string, newPassword: string) =>
    http.post<void>('/auth/forgot-password/reset', { username, code, newPassword }),

  /** 邀请注册页工号/学号可用性校验（公开，需有效邀请令牌防开放枚举） */
  checkUsername: (token: string, linkType: RegisterLinkType, studentId: string) =>
    http.get<UsernameAvailabilityResult>('/auth/register/check-username', { token, linkType, studentId }),
}
