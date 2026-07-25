import { http } from '@/utils/request'

export interface PhysicalInstance {
  id: number
  instanceNumber: string
  machineName?: string
  ipAddress?: string
  osInfo?: string
  gpuInfo?: string
  connectMode: string
  status: string
  agentVersion?: string
  lastHeartbeat?: string
  lastStatus?: string
  storageRoot?: string
  localAdminPasswordHash?: string
}

export interface PowerShellResult {
  success: boolean
  output: string
  error?: string
}

export const instanceApi = {
  list: () => http.get<PhysicalInstance[]>('/instances'),
  get: (id: number) => http.get<PhysicalInstance>(`/instances/${id}`),
  updateNumber: (id: number, number: string) => http.put(`/instances/${id}/number`, { number }),
  restart: (id: number) => http.post<PowerShellResult>(`/instances/${id}/restart`),
  screenOff: (id: number) => http.post<PowerShellResult>(`/instances/${id}/screen-off`),
  powershell: (id: number, command: string) => http.post<PowerShellResult>(`/instances/${id}/powershell`, { command }),
}
