import { http } from '@/utils/request'

export interface AgentLogFile {
  name: string
  size: number
  modTime: string
}

export interface AgentLogTail {
  date: string
  file: string
  tailLines: number
  content: string
}

export const agentLogApi = {
  listFiles(instanceId: number): Promise<AgentLogFile[]> {
    return http.get<AgentLogFile[]>(`/admin/agent-logs/instances/${instanceId}/files`)
  },
  tail(instanceId: number, date?: string, tailLines?: number): Promise<AgentLogTail> {
    return http.get<AgentLogTail>(`/admin/agent-logs/instances/${instanceId}/tail`, {
      date,
      tailLines,
    })
  },
}
