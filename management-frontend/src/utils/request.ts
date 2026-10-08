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

// agent-defaults：登录失效跳转中标志——首个失效请求弹提示+登出+跳转，并发后续静默拒绝，
// 防多请求同时失效时 toast 轰炸与重复跳转
let redirectingToLogin = false

// handleSessionExpired 统一处理登录失效（HTTP 401 / 业务码 1003）：
// 仅首次提示「登录已失效，请重新登录」并登出跳转登录页。
function handleSessionExpired(): void {
  if (!redirectingToLogin) {
    redirectingToLogin = true
    message.error('登录已失效，请重新登录')
    const auth = useAuthStore()
    auth.logout()
    window.location.href = '/login'
  }
}

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
        // 登录失效：提示后登出跳转（agent-defaults：文案改「登录已失效」避免与权限拒绝混淆）
        handleSessionExpired()
        const e = new Error(result.message || '登录已失效，请重新登录')
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
        if (code === 1003 || code === 401) {
          // token 失效：登出跳转（agent-defaults：统一走会话失效处理，并发仅提示一次）
          handleSessionExpired()
        } else {
          message.error(data.message || '请求失败')
        }
        const bizErr = new Error(data.message || '请求失败')
        ;(bizErr as any).code = code
        return Promise.reject(bizErr)
      }
      // 非业务错误体（网关/容器返回的 401/403/5xx 无标准 body）
      if (status === 401) {
        handleSessionExpired()
      } else if (status === 403) {
        // 防御性保留：修复后端 401 入口点后不应再出现"空 body 的 403"（未认证被误判为 403）
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
