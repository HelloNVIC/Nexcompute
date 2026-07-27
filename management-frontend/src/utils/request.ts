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
        // 登录失效：先提示"无权限执行此操作"再 logout + 跳转登录
        message.error('无权限执行此操作')
        const auth = useAuthStore()
        auth.logout()
        window.location.href = '/login'
        const e = new Error(result.message || '无权限执行此操作')
        ;(e as any).code = result.code
        return Promise.reject(e)
      }
      message.error(result.message || '请求失败')
      const bizErr = new Error(result.message || '请求失败')
      ;(bizErr as any).code = result.code
      return Promise.reject(bizErr)
    }
    return response
  },
  (error) => {
    if (error.response) {
      const data = error.response.data
      const status = error.response.status
      // 业务错误体（含 code 字段）：按业务码提示，不触发登出
      // （如登录密码错误 1001 / 账号禁用 1002 / 账号不存在 3001 -- 均应提示真实原因）
      if (data && typeof data.code !== 'undefined' && data.code !== 0) {
        const code = data.code
        if (code === 1003) {
          // token 失效：登出跳转
          message.error('登录已失效，请重新登录')
          const auth = useAuthStore()
          auth.logout()
          window.location.href = '/login'
        } else {
          message.error(data.message || '请求失败')
        }
        const bizErr = new Error(data.message || '请求失败')
        ;(bizErr as any).code = code
        return Promise.reject(bizErr)
      }
      // 非业务错误体（网关/容器返回的 401/403/5xx 无标准 body）
      if (status === 401) {
        message.error('登录已失效，请重新登录')
        const auth = useAuthStore()
        auth.logout()
        window.location.href = '/login'
      } else if (status === 403) {
        message.error('无权限执行此操作')
      } else {
        message.error(data?.message || error.message)
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
