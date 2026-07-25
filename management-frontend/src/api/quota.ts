import { http } from '@/utils/request'

/** 有效配额（每维可空=不限；用于创建容器表单默认值与上限） */
export interface EffectiveQuota {
  maxCpuCores?: number
  maxMemoryMb?: number
  maxGpuMemoryMb?: number
  maxShmMb?: number
}

/** 用户/课题组配额（管理员设置，可空=不限） */
export interface ResourceQuota {
  id?: number
  scope: 'USER' | 'GROUP'
  ownerId: number
  maxCpuCores?: number
  maxMemoryMb?: number
  maxGpuMemoryMb?: number
  maxShmMb?: number
}

export const quotaApi = {
  // 当前用户在目标物理实例上的有效配额（任务 2.5 默认值与范围）
  effective: (instanceId: number) => http.get<EffectiveQuota>('/quotas/effective', { instanceId }),
  get: (scope: string, ownerId: number) => http.get<ResourceQuota>('/quotas', { scope, ownerId }),
  upsert: (data: ResourceQuota) => http.put<ResourceQuota>('/quotas', data),
  delete: (id: number) => http.delete(`/quotas/${id}`),
}
