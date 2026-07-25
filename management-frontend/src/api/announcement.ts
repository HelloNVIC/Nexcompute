import { http } from '@/utils/request'

export interface Announcement {
  id: number
  title: string
  content: string
  targetScope: string  // ALL / GROUP / ROLE
  targetId?: number
  targetRole?: string
  publishMode: string  // IMMEDIATE / SCHEDULED
  publishAt?: string
  status: string       // PENDING / PUBLISHED
  authorName?: string
  createdAt: string
}

export const announcementApi = {
  list: () => http.get<Announcement[]>('/announcements'),
  get: (id: number) => http.get<Announcement>(`/announcements/${id}`),
  publish: (data: Record<string, unknown>) => http.post<Announcement>('/announcements', data),
  update: (id: number, data: Record<string, unknown>) => http.put(`/announcements/${id}`, data),
  delete: (id: number) => http.delete(`/announcements/${id}`),
  listAll: () => http.get<Announcement[]>('/announcements/all'),
}
