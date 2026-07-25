import { http } from '@/utils/request'

export interface NotificationMessage {
  id: number
  userId: number
  type: string  // CONTAINER / STORAGE_POOL / TICKET / ANNOUNCEMENT
  refId?: number
  title?: string
  content: string
  isRead: boolean
  createdAt: string
}

export const notificationApi = {
  unread: () => http.get<NotificationMessage[]>('/notifications/unread'),
  all: (page = 0, size = 20) => http.get<{ content: NotificationMessage[]; totalElements: number }>('/notifications', { page, size }),
  unreadCount: () => http.get<{ count: number }>('/notifications/unread-count'),
  markAsRead: (id: number) => http.post(`/notifications/${id}/read`),
  markAllAsRead: () => http.post('/notifications/read-all'),
}
