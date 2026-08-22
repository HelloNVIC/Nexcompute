import { http } from '@/utils/request'

export interface ResearchGroup {
  id: number
  name: string
  description?: string
  mentorId?: number
  /** 导师姓名（listAllWithDetails 解析 mentor_id -> realName，仅课题组管理表用） */
  mentorName?: string
  /** 已分配物理实例数量（按 instance_id 去重，仅课题组管理表用） */
  allocatedInstanceCount?: number
}

export interface UserInfoDto {
  id: number
  username: string
  realName: string
  role: string
  studentId?: string
  email?: string
  phone?: string
  groupId?: number
  groupName?: string
  status: string
}

export const groupApi = {
  my: () => http.get<ResearchGroup>('/groups/my'),
  list: () => http.get<ResearchGroup[]>('/groups'),
  get: (id: number) => http.get<ResearchGroup>(`/groups/${id}`),
  create: (data: { name: string; description?: string; mentorId?: number }) =>
    http.post<ResearchGroup>('/groups', data),
  update: (id: number, data: { name: string; description?: string }) => http.put<ResearchGroup>(`/groups/${id}`, data),
  remove: (id: number) => http.delete(`/groups/${id}`),
  students: (id: number) => http.get<UserInfoDto[]>(`/groups/${id}/students`),
  /** 按工号加入课题组成员（platform-refinements #6） */
  addMember: (groupId: number, workerId: string) =>
    http.post(`/groups/${groupId}/members`, { workerId }),
  /** 移除课题组成员（platform-refinements #6） */
  removeMember: (groupId: number, userId: number) =>
    http.delete(`/groups/${groupId}/members/${userId}`),
}
