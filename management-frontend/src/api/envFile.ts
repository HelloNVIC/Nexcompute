import { request } from '@/utils/request'

export interface EnvFile {
  id: number
  filename: string
  md5: string
  size: number
  uploadedAt?: string
  uploadedBy?: number
  uploadedByName?: string
}

export const envFileApi = {
  list: () => request<EnvFile[]>({ method: 'GET', url: '/admin/env-files' }),
  upload: (file: File, onProgress?: (percent: number) => void) => {
    const form = new FormData()
    form.append('file', file)
    return request<EnvFile>({
      method: 'POST',
      url: '/admin/env-files',
      data: form,
      timeout: 0, // 大文件（Docker Desktop Installer ~600MB）不超时
      onUploadProgress: (e: { loaded: number; total?: number }) => {
        if (onProgress && e.total) {
          onProgress(Math.round((e.loaded / e.total) * 100))
        }
      },
    })
  },
  remove: (id: number) => request<void>({ method: 'DELETE', url: `/admin/env-files/${id}` }),
  sync: () => request<{ sent: number }>({ method: 'POST', url: '/admin/env-files/sync' }),
}
