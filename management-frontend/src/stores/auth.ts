import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { http } from '@/utils/request'

export type UserRole = 'ADMIN' | 'MENTOR' | 'STUDENT'

export interface UserInfo {
  id: number
  username: string
  realName: string
  role: UserRole
  studentId?: string
  groupId?: number
  groupName?: string
  email?: string
  phone?: string
}

export const useAuthStore = defineStore('auth', () => {
  const token = ref<string | null>(localStorage.getItem('nex_token'))
  const user = ref<UserInfo | null>(
    JSON.parse(localStorage.getItem('nex_user') ?? 'null'),
  )

  const isLoggedIn = computed(() => !!token.value)
  const role = computed(() => user.value?.role)

  async function login(username: string, password: string): Promise<void> {
    const data = await http.post<{ token: string; user: UserInfo }>('/auth/login', {
      username,
      password,
    })
    token.value = data.token
    user.value = data.user
    localStorage.setItem('nex_token', data.token)
    localStorage.setItem('nex_user', JSON.stringify(data.user))
  }

  function setToken(t: string): void {
    token.value = t
    localStorage.setItem('nex_token', t)
  }

  function setUser(u: UserInfo): void {
    user.value = u
    localStorage.setItem('nex_user', JSON.stringify(u))
  }

  function logout(): void {
    token.value = null
    user.value = null
    localStorage.removeItem('nex_token')
    localStorage.removeItem('nex_user')
  }

  /** 权限矩阵：检查角色对某模块是否有某操作权限 */
  function hasPermission(_module: string, _action: 'view' | 'edit' | 'delete'): boolean {
    // TODO: 接入权限矩阵（任务 2.3/2.4 后由后端返回矩阵，前端缓存）
    return true
  }

  return {
    token,
    user,
    isLoggedIn,
    role,
    login,
    setToken,
    setUser,
    logout,
    hasPermission,
  }
})
