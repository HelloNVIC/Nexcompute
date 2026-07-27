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

// 公告已读/未读名单项
export interface ReadStatusItem {
  userId: number
  realName: string
  username: string
  email?: string
  readAt?: string
}

// 公告已读/未读视图
export interface ReadStatusView {
  total: number
  readCount: number
  unreadCount: number
  read: ReadStatusItem[]
  unread: ReadStatusItem[]
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
  // 公告已读/未读名单（管理员）
  readStatus: (id: number) => http.get<ReadStatusView>(`/announcements/${id}/read-status`),
  // 对未读名单发送邮件提醒（管理员）
  remind: (id: number) => http.post<{ reminded: number }>(`/announcements/${id}/remind`),
}
