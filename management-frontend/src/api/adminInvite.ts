import { http } from '@/utils/request'

export interface AdminRegistrationLink {
  id: number
  token: string
  creatorId: number
  remainingCount: number
  expireAt: string
  status: string
  createdAt: string
}

export interface CreateAdminLinkPayload {
  remainingCount: number
  expireAt: string
}

export const adminInviteApi = {
  list: () => http.get<AdminRegistrationLink[]>('/admin/admin-registration-links'),
  create: (data: CreateAdminLinkPayload) =>
    http.post<AdminRegistrationLink>('/admin/admin-registration-links', data),
  revoke: (token: string) =>
    http.post<void>(`/admin/admin-registration-links/${token}/revoke`),
  /** 拼接待注册链接（管理员注册页 URL，表单复用学生注册字段） */
  registerUrl: (token: string) => `${window.location.origin}/admin-register?token=${token}`,
}
