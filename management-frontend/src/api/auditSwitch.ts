import { http } from '@/utils/request'

export interface AuditSwitchStatus {
  enabled: boolean
}

export const auditSwitchApi = {
  status(): Promise<AuditSwitchStatus> {
    return http.get<AuditSwitchStatus>('/admin/audit-switch')
  },
  toggle(enabled: boolean): Promise<AuditSwitchStatus> {
    return http.post<AuditSwitchStatus>('/admin/audit-switch', undefined, { enabled })
  },
}
