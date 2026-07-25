import axios, { type AxiosInstance, type AxiosRequestConfig, type AxiosResponse } from 'axios'
import { message } from 'ant-design-vue'
import { useAuthStore } from '@/stores/auth'

export interface ApiResult<T = unknown> {
  code: number
  message: string
  data: T
  timestamp?: number
}

const BASE_URL = import.meta.env.VITE_API_BASE ?? '/api'

const instance: AxiosInstance = axios.create({
  baseURL: BASE_URL,
  timeout: 30000,
})

instance.interceptors.request.use((config) => {
  const auth = useAuthStore()
  if (auth.token) {
    config.headers.Authorization = `Bearer ${auth.token}`
  }
  return config
})

instance.interceptors.response.use(
  (response: AxiosResponse<ApiResult>) => {
    const result = response.data
    if (result && typeof result.code !== 'undefined') {
      if (result.code === 0) {
        return result as unknown as AxiosResponse
      }
      // 业务错误
      if (result.code === 1003 || result.code === 401) {
        const auth = useAuthStore()
        auth.logout()
        window.location.href = '/login'
      }
      message.error(result.message || '请求失败')
      return Promise.reject(new Error(result.message || '请求失败'))
    }
    return response
  },
  (error) => {
    if (error.response) {
      const status = error.response.status
      if (status === 401) {
        const auth = useAuthStore()
        auth.logout()
        window.location.href = '/login'
      } else if (status === 403) {
        message.error('无权限执行此操作')
      } else {
        const msg = error.response.data?.message || error.message
        message.error(msg)
      }
    } else {
      message.error('网络异常，请检查连接')
    }
    return Promise.reject(error)
  },
)

export async function request<T = unknown>(config: AxiosRequestConfig): Promise<T> {
  const response = await instance.request<ApiResult<T>>(config)
  return (response as unknown as ApiResult<T>).data
}

export const http = {
  get<T = unknown>(url: string, params?: Record<string, unknown>) {
    return request<T>({ method: 'GET', url, params })
  },
  post<T = unknown>(url: string, data?: unknown, params?: Record<string, unknown>) {
    return request<T>({ method: 'POST', url, data, params })
  },
  put<T = unknown>(url: string, data?: unknown) {
    return request<T>({ method: 'PUT', url, data })
  },
  patch<T = unknown>(url: string, data?: unknown) {
    return request<T>({ method: 'PATCH', url, data })
  },
  delete<T = unknown>(url: string, params?: Record<string, unknown>) {
    return request<T>({ method: 'DELETE', url, params })
  },
}

export default instance
