import { http } from '@/utils/request'
import type { UserInfoDto, UserRole } from '@/types'
import type { ResearchGroup } from '@/api/group'

export interface CreateUserPayload {
  username: string
  password: string
  realName: string
  role: UserRole
  studentId?: string
  email?: string
  phone?: string
  groupId?: number
}

export const userApi = {
  list: (params?: { role?: UserRole; page?: number; size?: number }) =>
    http.get<{ content: UserInfoDto[]; totalElements: number }>('/admin/users', params),
  get: (id: number) => http.get<UserInfoDto>(`/admin/users/${id}`),
  /** 用户所属课题组列表（platform-refinements 9.2） */
  userGroups: (id: number) => http.get<ResearchGroup[]>(`/admin/users/${id}/groups`),
  create: (data: CreateUserPayload) => http.post<UserInfoDto>('/admin/users', data),
  update: (id: number, data: Partial<CreateUserPayload> & { status?: string }) =>
    http.put<UserInfoDto>(`/admin/users/${id}`, data),
  disable: (id: number) => http.post(`/admin/users/${id}/disable`),
  /** 删除用户（platform-refinements #3） */
  remove: (id: number) => http.delete(`/admin/users/${id}`),
  /** 管理用户课题组归属（platform-refinements 9.1） */
  updateGroups: (id: number, groupIds: number[]) =>
    http.put<UserInfoDto>(`/admin/users/${id}/groups`, { groupIds }),
  /** 重置用户密码（platform-refinements 9.1） */
  resetPassword: (id: number, password: string) =>
    http.post(`/admin/users/${id}/reset-password`, { password }),
}

/** 受控端管理密码（platform-refinements 11.1） */
export const localAdminPasswordApi = {
  status: () => http.get<{ set: boolean }>('/admin/local-admin-password'),
  set: (password: string) =>
    http.post('/admin/local-admin-password', { password }),
}
