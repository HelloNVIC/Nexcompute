import { http } from '@/utils/request'

export interface AuditLogDto {
  id: number
  source: 'HOT' | 'ARCHIVE'
  operatorId?: number
  operatorName?: string
  operatorRole?: string
  action: string
  targetType?: string
  targetId?: string
  content?: string
  result: string
  errorMessage?: string
  ipAddress?: string
  operationNo?: string
  clientInfo?: string
  mentorIdAtOp?: number
  createdAt: string
}

export interface AuditLogPage {
  content: AuditLogDto[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}

export interface AuditLogQuery {
  start?: string
  end?: string
  operatorId?: number
  operatorName?: string
  operatorRole?: string
  action?: string
  targetType?: string
  targetId?: string
  result?: string
  clientInfo?: string
  mentorIdAtOp?: number
  operationNo?: string
  keyword?: string
  sortBy?: string
  sortDir?: 'asc' | 'desc'
  page?: number
  size?: number
}

export const auditLogApi = {
  list(params: AuditLogQuery): Promise<AuditLogPage> {
    return http.get<AuditLogPage>('/audit-logs', params as Record<string, unknown>)
  },
}
