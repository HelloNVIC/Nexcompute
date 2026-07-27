import { http } from '@/utils/request'

export interface MentorRegistrationLink {
  id: number
  token: string
  creatorId: number
  remainingCount: number
  expireAt: string
  status: string
  createdAt: string
}

export interface CreateMentorLinkPayload {
  remainingCount: number
  expireAt: string
}

export const mentorInviteApi = {
  list: () => http.get<MentorRegistrationLink[]>('/admin/mentor-registration-links'),
  create: (data: CreateMentorLinkPayload) =>
    http.post<MentorRegistrationLink>('/admin/mentor-registration-links', data),
  revoke: (token: string) =>
    http.post<void>(`/admin/mentor-registration-links/${token}/revoke`),
  /** 拼接待注册链接（导师注册页 URL） */
  registerUrl: (token: string) => `${window.location.origin}/mentor-register?token=${token}`,
}
