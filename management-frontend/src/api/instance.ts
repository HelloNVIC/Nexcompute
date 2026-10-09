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
  /** 机器码（硬件指纹，受控端心跳上报） */
  machineCode?: string
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
  /** 删除物理实例（instance-identity，仅管理员；在线/被占用会被拒绝）。force=true 跳过镜像同步
   *  任务检查并连同删除（同步任务为纯派生记录，前端确认删除即强制） */
  delete: (id: number, force = false) => http.delete(`/instances/${id}`, force ? { force: true } : undefined),
}
