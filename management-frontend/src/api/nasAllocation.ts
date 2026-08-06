import { http } from '@/utils/request'

// ============ NAS 邀请 ============
export interface NasInvitation {
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

export interface CreateNasInvitationPayload {
  label: string
  maxUses: number
  expireAt: string
}

// ============ 公开注册 ============
export interface NasRegisterFormMeta {
  token: string
  valid: boolean
  label: string
  remaining: number
  expiresAt: string
}

export interface NasRegisterSubmitPayload {
  token: string
  username: string
  fullName: string
  email: string
  phone: string
  password: string
}

export interface NasRegistrationSubmitResponse {
  registrationId: number
  message: string
  truenasLoginUrl: string
}

// ============ 用户名可用性检查 ============
export type NasUsernameCheckCode =
  | 'AVAILABLE' | 'INVALID' | 'LOCAL_TAKEN' | 'TRUENAS_TAKEN' | 'UNREACHABLE' | 'ERROR'

export interface NasUsernameCheckResult {
  available: boolean
  code: NasUsernameCheckCode
  message: string
}

// ============ 注册申请（管理员） ============
export type NasRegistrationStatus =
  | 'PENDING' | 'APPROVED' | 'REJECTED' | 'FAILED' | 'NOT_FOUND'

export interface NasRegistrationListItem {
  id: number
  invitationId: number
  username: string
  fullName: string
  email: string
  phone: string
  status: NasRegistrationStatus
  truenasUserId: number | null
  truenasUid: number | null
  submittedAt: string
  reviewedBy: number | null
  reviewedAt: string | null
  rejectReason: string | null
  provisionError: string | null
  hasPassword: boolean
}

export interface NasRegistrationDetail {
  registration: NasRegistrationListItem
  truenas: Record<string, unknown> | null
  truenasLoginUrl: string
}

export interface NasRegistrationApproveResponse {
  message: string
  registrationId: number
  status: NasRegistrationStatus
  truenasUserId: number | null
  truenasUid: number | null
  truenasLoginUrl: string
  webuiRole: string | null
}

export interface NasRefreshStatusesResult {
  checked: number
  flipped: number
}

// TrueNAS Web 后台角色组 id
export const WEBUI_ROLE = {
  FULL_ADMIN: 40,
  READONLY_ADMIN: 41,
  SHARING_ADMIN: 42,
} as const

export const nasAllocationApi = {
  // ---- 邀请 ----
  listInvitations: () => http.get<NasInvitation[]>('/admin/nas-invitations'),
  createInvitation: (data: CreateNasInvitationPayload) =>
    http.post<NasInvitation>('/admin/nas-invitations', data),
  getInvitation: (id: number) => http.get<NasInvitation>(`/admin/nas-invitations/${id}`),
  revokeInvitation: (id: number) => http.post<void>(`/admin/nas-invitations/${id}/revoke`),

  // ---- 公开注册 ----
  getRegisterForm: (token: string) =>
    http.get<NasRegisterFormMeta>('/nas-allocation/register', { token }),
  submitRegister: (data: NasRegisterSubmitPayload) =>
    http.post<NasRegistrationSubmitResponse>('/nas-allocation/register', data),
  /** 用户名可用性检查（输入后 onBlur 实时调用：本地占用 + TrueNAS 查重） */
  checkUsername: (username: string) =>
    http.get<NasUsernameCheckResult>('/nas-allocation/register/check-username', { username }),

  // ---- 注册申请（管理员） ----
  listRegistrations: (status?: string) =>
    http.get<NasRegistrationListItem[]>('/admin/nas-registrations', status ? { status } : undefined),
  getRegistrationDetail: (id: number) =>
    http.get<NasRegistrationDetail>(`/admin/nas-registrations/${id}`),
  approve: (id: number, webuiGroupId: number | null) =>
    http.post<NasRegistrationApproveResponse>(`/admin/nas-registrations/${id}/approve`, { webuiGroupId }),
  reapprove: (id: number, webuiGroupId: number | null) =>
    http.post<NasRegistrationApproveResponse>(`/admin/nas-registrations/${id}/reapprove`, { webuiGroupId }),
  reprovision: (id: number, password: string, webuiGroupId: number | null) =>
    http.post<NasRegistrationApproveResponse>(`/admin/nas-registrations/${id}/reprovision`, { password, webuiGroupId }),
  reject: (id: number, rejectReason: string) =>
    http.post<NasRegistrationListItem>(`/admin/nas-registrations/${id}/reject`, { rejectReason }),
  deleteRegistration: (id: number) => http.delete<void>(`/admin/nas-registrations/${id}`),
  refreshStatuses: () => http.post<NasRefreshStatusesResult>('/admin/nas-registrations/refresh-statuses'),
}
