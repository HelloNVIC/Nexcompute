import { http } from '@/utils/request'

export interface SystemInfo {
  maintainer?: string
  maintainerPhone?: string
  owner?: string
  ownerPhone?: string
  updatedAt?: string
}

export const systemInfoApi = {
  get: () => http.get<SystemInfo>('/system-info'),
}
