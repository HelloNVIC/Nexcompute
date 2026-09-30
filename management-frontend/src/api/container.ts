import { http, request } from '@/utils/request'

// 长耗时同步接口超时：后端同步编排（镜像拉取 600s + 容器创建 120s / commit 推送仓库 30min），
// axios 默认 30s 会在等待期误报"网络异常"，需覆盖后端最长阻塞时间
const CREATE_TIMEOUT = 15 * 60 * 1000 // 拉取 600s + 创建 120s + 余量
const COMMIT_TIMEOUT = 31 * 60 * 1000 // commit tag+push 后端 30min 超时 + 余量
const LIFECYCLE_TIMEOUT = 2 * 60 * 1000 // 后端 lifecycle 60s；大容器 stop/rm 可能超 axios 默认 30s

export interface Container {
  id: number
  name: string
  ownerId: number
  ownerName?: string
  /** 共有人（platform-refinements #2） */
  sharedWith?: ContainerShareView[]
  instanceId: number
  instanceNumber: string
  imageRef: string
  storagePoolId?: number
  /** 关联存储池名（V31，列表展示用，enrichContainers 批量填充） */
  storagePoolName?: string
  /** 存储池在容器内的挂载点（V31，docker bind mount Target） */
  mountPoint?: string
  projectName?: string
  cpuLimit?: number
  memoryLimit?: number
  gpuMemoryLimit?: number
  shmSize?: number
  portMappings?: string
  sshPassword?: string
  dockerId?: string
  /** 备注（platform-refinements #1） */
  remark?: string
  /** 物理实例是否在线（platform-refinements #7） */
  instanceOnline?: boolean
  status: string
  createdAt: string
}

/** 容器共享视图（platform-refinements #2） */
export interface ContainerShareView {
  id: number
  userId: number
  realName: string
  workerId?: string
  expiresAt?: string
  expired: boolean
}

export interface ConnectionInfo {
  ssh?: { host: string; port: number }
  apps?: Array<{ name: string; containerPort: number; hostPort: number; url: string }>
  mode: string
}

export const containerApi = {
  list: () => http.get<Container[]>('/containers'),
  get: (id: number) => http.get<Container>(`/containers/${id}`),
  /** 创建容器（同步编排：必要时先经私有仓库拉取镜像，最长 ~12min，等待期有 SSE 拉取进度） */
  create: (data: Record<string, unknown>) =>
    request<Container>({ method: 'POST', url: '/containers', data, timeout: CREATE_TIMEOUT }),
  connectionInfo: (id: number) => http.get<ConnectionInfo>(`/containers/${id}/connection`),
  formSnapshot: (id: number) => http.get<string>(`/containers/${id}/form-snapshot`),
  lifecycle: (id: number, action: string) =>
    request<unknown>({ method: 'POST', url: `/containers/${id}/${action}`, timeout: LIFECYCLE_TIMEOUT }),
  resetSsh: (id: number, password: string) => http.post(`/containers/${id}/reset-ssh`, { password }),
  /** 容器提交镜像持久化（registry：commit 后 tag+push 私有仓库，后端 30min 超时） */
  commitImage: (id: number, data: { imageName: string; imageTag?: string; project?: string; note?: string }) =>
    request<Container>({ method: 'POST', url: `/containers/${id}/commit-image`, data, timeout: COMMIT_TIMEOUT }),
  /** 共享容器（platform-refinements #2：按工号，可限时） */
  share: (id: number, data: { targetWorkerId: string; expiresAt?: string }) =>
    http.post(`/containers/${id}/share`, data),
  /** 取消共享（platform-refinements #2） */
  unshare: (id: number, shareId: number) => http.delete(`/containers/${id}/share/${shareId}`),
  /** 查看容器日志（platform-refinements #2） */
  logs: (id: number, tail: number) => http.get<string>(`/containers/${id}/logs`, { tail }),
  /** 修改容器备注（platform-refinements #1） */
  updateRemark: (id: number, remark: string) => http.put(`/containers/${id}/remark`, { remark }),
  // 在线容器终端（platform-refinements #2：交互式 exec，轮询 read/write）
  terminalOpen: (id: number, shell: string) =>
    http.post<string>(`/containers/${id}/terminal/open`, undefined, { shell }),
  terminalWrite: (id: number, sessionId: string, data: string) =>
    http.post(`/containers/${id}/terminal/write`, { sessionId, data }),
  terminalRead: (id: number, sessionId: string) =>
    http.get<string>(`/containers/${id}/terminal/read`, { sessionId }),
  terminalClose: (id: number, sessionId: string) =>
    http.post(`/containers/${id}/terminal/close`, { sessionId }),
  aggregation: (instanceId: number) =>
    http.get<{ myContainers: number; otherUsers: number; otherContainers: number }>(`/containers/instances/${instanceId}/aggregation`),
}
