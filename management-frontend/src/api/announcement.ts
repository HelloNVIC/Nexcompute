import { http } from '@/utils/request'

export interface Announcement {
  id: number
  title: string
  content: string
  targetScope: string  // ALL / GROUP / ROLE
  targetId?: number
  targetGroupIds?: number[]  // D10：GROUP 多选课题组
  targetGroupNames?: string[]  // D10：列表展示多课题组名
  targetRole?: string
  publishMode: string  // IMMEDIATE / SCHEDULED
  publishAt?: string
  status: string       // PENDING / PUBLISHED
  authorName?: string
  createdAt: string
}

// D9 登录公告中央弹窗项
export interface AnnouncementBanner {
  announcementId: number
  notificationId: number
  title: string
  content: string
  authorName?: string
  publishedAt?: string
}

export const announcementApi = {
  list: () => http.get<Announcement[]>('/announcements'),
  get: (id: number) => http.get<Announcement>(`/announcements/${id}`),
  publish: (data: Record<string, unknown>) => http.post<Announcement>('/announcements', data),
  update: (id: number, data: Record<string, unknown>) => http.put(`/announcements/${id}`, data),
  delete: (id: number) => http.delete(`/announcements/${id}`),
  listAll: () => http.get<Announcement[]>('/announcements/all'),
  // D9 登录公告未读列表
  loginBanner: () => http.get<AnnouncementBanner[]>('/announcements/login-banner'),
}
