import { http, request } from '@/utils/request'

export interface StoragePool {
  id: number
  poolName: string
  projectName: string
  ownerId: number
  instanceId: number
  instanceNumber: string
  userStudentId: string
  poolPath?: string
  status: string
  /** 离线派生标识（计算型，不写入存储状态）；与 status 共存展示 */
  offline?: boolean
  createdAt: string
}

export interface PoolFileEntry {
  name: string
  isDir: boolean
  size: number
  modTime: string
}

export interface RevokeShareResult {
  success: boolean
  blocked: boolean
  poolId: number
  targetUserId: number
  blockingContainers?: Array<{ id: number; name: string }>
  contactInfo?: string
}

export const storagePoolApi = {
  list: () => http.get<StoragePool[]>('/storage-pools'),
  create: (data: { instanceId: number; projectName: string }) =>
    http.post<StoragePool>('/storage-pools', data),
  share: (poolId: number, targetUserId: number) =>
    http.post(`/storage-pools/${poolId}/share`, { targetUserId }),
  revoke: (poolId: number, targetUserId: number) =>
    http.post<RevokeShareResult>(`/storage-pools/${poolId}/revoke`, { targetUserId }),
  migrate: (poolId: number, targetInstanceId: number) =>
    http.post(`/storage-pools/${poolId}/migrate`, { targetInstanceId }),
  confirmMigration: (poolId: number) =>
    http.post(`/storage-pools/${poolId}/migrate/confirm`),
  /** 删除存储池（platform-refinements 7.3：前置检查无运行容器依赖；force=true 仅管理员，用于实例离线时强制删除） */
  remove: (poolId: number, force = false) => http.delete(`/storage-pools/${poolId}`, { force }),
  // 文件管理（platform-refinements #3）
  files: (poolId: number, path: string) =>
    http.get<PoolFileEntry[]>(`/storage-pools/${poolId}/files`, { path }),
  // 异步打包下载（platform-refinements #3：start -> 轮询 progress -> 取 file）
  downloadStart: (poolId: number, path: string) =>
    http.post<{ transferId: string }>(`/storage-pools/${poolId}/download/start`, undefined, { path }),
  downloadProgress: (transferId: string) =>
    http.get<{ status: string; percent: number; fileName: string; totalBytes: number; doneBytes: number }>(
      `/storage-pools/download/progress`,
      { transferId },
    ),
  downloadFile: (transferId: string) =>
    request<Blob>({
      method: 'GET',
      url: `/storage-pools/download/file`,
      params: { transferId },
      responseType: 'blob',
      timeout: 600000,
    }),
  uploadFile: (poolId: number, path: string, relativePath: string, file: File) => {
    const formData = new FormData()
    formData.append('file', file)
    return http.post(`/storage-pools/${poolId}/upload`, formData, { path, relativePath })
  },
}
