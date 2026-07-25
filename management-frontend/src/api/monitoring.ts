import { http } from '@/utils/request'

export interface MonitoringHistory {
  id: number
  instanceId: number
  instanceNumber: string
  cpuUsage?: number
  cpuTemp?: number
  gpuUsage?: number
  gpuTemp?: number
  memoryUsage?: number
  memoryTotal?: number
  memoryUsed?: number
  /** 结构化 GPU 显存（MiB，platform-improvements 任务 4.2/4.5） */
  gpuMemoryTotal?: number
  gpuMemoryUsed?: number
  recordedAt: string
}

export const monitoringApi = {
  history: (instanceId: number, start?: string, end?: string) =>
    http.get<MonitoringHistory[]>(`/monitoring/instances/${instanceId}/history`, { start, end }),
  recent: (instanceId: number) => http.get<MonitoringHistory[]>(`/monitoring/instances/${instanceId}/recent`),
  processes: (instanceId: number) => http.get<string>(`/monitoring/instances/${instanceId}/processes`),
  config: () => http.get<{ collectIntervalSeconds: number }>('/monitoring/config'),
}
