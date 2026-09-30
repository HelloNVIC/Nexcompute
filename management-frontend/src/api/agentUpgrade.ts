import { request } from '@/utils/request'

export interface AgentVersion {
  version: string
  md5: string
  size: number
}

export interface AgentUpgradeTask {
  id: number
  instanceId: number
  instanceNumber?: string
  version: string
  md5?: string
  status: string // PENDING / SUCCESS / FAILED
  error?: string
  /** 当前升级阶段：downloading/verifying/backing_up/replacing/waiting（D2） */
  progressStage?: string
  /** 各段百分比 JSON：{"downloading":45,"verifying":0,"backing_up":0,"replacing":0,"waiting":0} */
  stagePercents?: string
  createdAt?: string
  finishedAt?: string
}

export const agentUpgradeApi = {
  versions: () => request<AgentVersion[]>({ method: 'GET', url: '/admin/agent-upgrade/versions' }),
  deleteVersion: (version: string) =>
    request<void>({ method: 'DELETE', url: `/admin/agent-upgrade/versions/${encodeURIComponent(version)}` }),
  upload: (file: File, version: string, onProgress?: (percent: number) => void) => {
    const form = new FormData()
    form.append('file', file)
    return request<{ version: string; md5: string; size: number; downloadUrl: string }>({
      method: 'POST',
      url: '/admin/agent-upgrade/upload',
      data: form,
      params: { version },
      timeout: 0,
      onUploadProgress: (e: { loaded: number; total?: number }) => {
        if (onProgress && e.total) {
          onProgress(Math.round((e.loaded / e.total) * 100))
        }
      },
    })
  },
  upgrade: (instanceIds: number[], version: string) =>
    request<AgentUpgradeTask[]>({
      method: 'POST',
      url: '/admin/agent-upgrade/upgrade',
      data: { instanceIds, version },
      timeout: 0, // 后端并发同时升级全部实例，耗时=单实例上限（命令超时+版本等待），不设 axios 限时
    }),
  tasks: () => request<AgentUpgradeTask[]>({ method: 'GET', url: '/admin/agent-upgrade/tasks' }),
}
