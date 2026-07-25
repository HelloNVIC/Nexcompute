import type { UserRole } from '@/stores/auth'

export type { UserRole }

export interface UserInfoDto {
  id: number
  username: string
  realName: string
  role: UserRole
  studentId?: string
  email?: string
  phone?: string
  groupId?: number
  groupName?: string
  status: string
  /** 注册时间（platform-refinements #5） */
  createdAt?: string
}

export interface PageResult<T> {
  content: T[]
  totalElements: number
  totalPages: number
  number: number
}
