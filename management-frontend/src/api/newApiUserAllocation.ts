import { http } from '@/utils/request'

// ============ NewAPI 邀请 ============
export interface NewApiInvitation {
  id: number
  token: string
  label: string
  maxUses: number
  usedCount: number
  remaining: number
  expiresAt: string
  createdAt: string
  revokedAt: string | null
  registerUrl: string
  valid: boolean
}

export interface CreateNewApiInvitationPayload {
  label: string
  maxUses: number
  expireAt: string
}

// ============ 公开注册 ============
export interface NewApiRegisterFormMeta {
  token: string
  valid: boolean
  label: string
  remaining: number
  expiresAt: string
}

export interface NewApiRegisterSubmitPayload {
  token: string
  username: string
  displayName: string
  email: string
  phone: string
  password: string
}

export interface NewApiRegistrationSubmitResponse {
  registrationId: number
  message: string
  newapiPortalUrl: string
}

// ============ 用户名可用性检查 ============
export type NewApiUsernameCheckCode =
  | 'AVAILABLE' | 'INVALID' | 'LOCAL_TAKEN' | 'NEWAPI_TAKEN' | 'UNREACHABLE' | 'ERROR'

export interface NewApiUsernameCheckResult {
  available: boolean
  code: NewApiUsernameCheckCode
  message: string
}

// ============ 注册申请（管理员） ============
export type NewApiRegistrationStatus =
  | 'PENDING' | 'APPROVED' | 'REJECTED' | 'FAILED' | 'NOT_FOUND'

export interface NewApiRegistrationListItem {
  id: number
  invitationId: number
  username: string
  displayName: string
  email: string
  phone: string
  status: NewApiRegistrationStatus
  newapiUserId: number | null
  newapiGroup: string | null
  submittedAt: string
  reviewedBy: number | null
  reviewedAt: string | null
  rejectReason: string | null
  provisionError: string | null
  hasPassword: boolean
}

export interface NewApiRegistrationDetail {
  registration: NewApiRegistrationListItem
  newapi: Record<string, unknown> | null
  newapiPortalUrl: string
}

export interface NewApiRegistrationApproveResponse {
  message: string
  registrationId: number
  status: NewApiRegistrationStatus
  newapiUserId: number | null
  newapiPortalUrl: string
  group: string | null
}

export interface NewApiRefreshStatusesResult {
  checked: number
  flipped: number
}

// NewAPI 分组常用项（实际分组由 NewUI 配置，本期硬编码；可在批准/改分组时自定义输入）
export const NEWAPI_GROUP_OPTIONS = ['default', 'vip'] as const

export const newApiUserAllocationApi = {
  // ---- 邀请 ----
  listInvitations: () => http.get<NewApiInvitation[]>('/admin/newapi-invitations'),
  createInvitation: (data: CreateNewApiInvitationPayload) =>
    http.post<NewApiInvitation>('/admin/newapi-invitations', data),
  getInvitation: (id: number) => http.get<NewApiInvitation>(`/admin/newapi-invitations/${id}`),
  revokeInvitation: (id: number) => http.post<void>(`/admin/newapi-invitations/${id}/revoke`),

  // ---- 公开注册 ----
  getRegisterForm: (token: string) =>
    http.get<NewApiRegisterFormMeta>('/newapi-allocation/register', { token }),
  submitRegister: (data: NewApiRegisterSubmitPayload) =>
    http.post<NewApiRegistrationSubmitResponse>('/newapi-allocation/register', data),
  /** 用户名可用性检查（输入后 onBlur 实时调用：本地占用 + NewAPI 查重） */
  checkUsername: (username: string) =>
    http.get<NewApiUsernameCheckResult>('/newapi-allocation/register/check-username', { username }),

  // ---- 注册申请（管理员） ----
  listRegistrations: (status?: string) =>
    http.get<NewApiRegistrationListItem[]>('/admin/newapi-registrations', status ? { status } : undefined),
  getRegistrationDetail: (id: number) =>
    http.get<NewApiRegistrationDetail>(`/admin/newapi-registrations/${id}`),
  approve: (id: number, group: string | null) =>
    http.post<NewApiRegistrationApproveResponse>(`/admin/newapi-registrations/${id}/approve`, { group }),
  reapprove: (id: number, group: string | null) =>
    http.post<NewApiRegistrationApproveResponse>(`/admin/newapi-registrations/${id}/reapprove`, { group }),
  reprovision: (id: number, password: string, group: string | null) =>
    http.post<NewApiRegistrationApproveResponse>(`/admin/newapi-registrations/${id}/reprovision`, { password, group }),
  reject: (id: number, rejectReason: string) =>
    http.post<NewApiRegistrationListItem>(`/admin/newapi-registrations/${id}/reject`, { rejectReason }),
  deleteRegistration: (id: number) => http.delete<void>(`/admin/newapi-registrations/${id}`),
  refreshStatuses: () => http.post<NewApiRefreshStatusesResult>('/admin/newapi-registrations/refresh-statuses'),
}
