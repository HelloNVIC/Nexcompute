import { http, request } from '@/utils/request'

export interface ImageMetadata {
  id: number
  name: string
  tag: string
  ownerId?: number
  ownerName?: string
  groupId?: number
  sizeBytes?: number
  tarPath?: string
  isPublic: boolean
  sourceContainer?: string
  checksum?: string
  status: string
  /** 应用端口列表（来自 tar ExposedPorts 解析），创建容器时预填容器内端口 */
  appPorts?: number[]
  /** 使用说明（platform-refinements 4.4） */
  usageInstructions?: string
  /** 容器内挂载点（V31）：存储池映射到容器内的路径，创建容器选该镜像时自动预填 */
  mountPoint?: string
  /** 备注（commit 镜像） */
  note?: string
  /** 所属项目（commit 镜像） */
  project?: string
  /** 来源工号（commit 镜像提交者） */
  sourceWorkerId?: string
  /** 可见性：PRIVATE / SHARED_TO_ALL / SHARED */
  visibility?: string
  createdAt: string
}

/** parse-tar 返回（platform-refinements 4.1） */
export interface ParsedTar {
  name: string
  tag: string
  appPorts: number[]
}

export const imageApi = {
  list: () => http.get<ImageMetadata[]>('/images'),
  get: (id: number) => http.get<ImageMetadata>(`/images/${id}`),
  /** 共享镜像（platform-refinements 6.6/#2：支持按 userId/工号(可多个)/课题组） */
  share: (
    id: number,
    target: { targetUserId?: number; targetWorkerIds?: string[]; targetGroupId?: number },
  ) => http.post(`/images/${id}/share`, target),
  /** 设置可见性（platform-refinements：全员 SHARED_TO_ALL / 私有 PRIVATE） */
  setVisibility: (id: number, visibility: 'SHARED_TO_ALL' | 'PRIVATE') =>
    http.post(`/images/${id}/visibility`, { visibility }),
  /** 下载镜像 tar（platform-refinements #2） */
  download: (id: number) =>
    request<Blob>({ method: 'GET', url: `/images/${id}/download`, responseType: 'blob', timeout: 600000 }),
  delete: (id: number) => http.delete(`/images/${id}`),
  // 选定 tar 即时解析（不落盘，platform-refinements 4.1）：回填 name/tag/appPorts
  parseTar: (file: File) => {
    const formData = new FormData()
    formData.append('file', file)
    return http.post<ParsedTar>('/images/parse-tar', formData)
  },
  // 上传 tar 文件作为镜像（任务 3）；name/tag 可空，后端解析 RepoTags 预填；
  // appPorts 可空，后端解析 ExposedPorts 预填；usageInstructions 使用说明（platform-refinements 4.2）；
  // mountPoint 容器内挂载点（V31），创建容器时自动预填
  uploadTar: (
    file: File,
    name: string,
    tag: string,
    appPorts?: number[],
    usageInstructions?: string,
    mountPoint?: string,
  ) => {
    const formData = new FormData()
    formData.append('file', file)
    if (name) formData.append('name', name)
    if (tag) formData.append('tag', tag)
    if (appPorts && appPorts.length) formData.append('appPorts', appPorts.join(','))
    if (usageInstructions) formData.append('usageInstructions', usageInstructions)
    if (mountPoint) formData.append('mountPoint', mountPoint)
    return http.post<ImageMetadata>('/images/upload-tar', formData)
  },
  /** 编辑镜像应用端口、使用说明与容器内挂载点 */
  editMetadata: (id: number, appPorts: number[], usageInstructions: string, mountPoint?: string) =>
    http.put<ImageMetadata>(`/images/${id}/metadata`, { appPorts, usageInstructions, mountPoint }),
}
