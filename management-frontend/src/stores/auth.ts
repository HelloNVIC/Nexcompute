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
    void fetchPermissions()
    // D9：新会话重置"下次再说"公告忽略列表（本会话已忽略的不再跨登录保留）
    localStorage.removeItem('dismissedAnnouncements')
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

  /** 权限矩阵缓存（moduleCode -> 三操作）；复核反馈：原先为 TODO 桩恒 true，现真实现 */
  const perms = ref<Record<string, { canView: boolean; canEdit: boolean; canDelete: boolean }> | null>(null)

  /** 拉取当前用户角色的权限矩阵（登录后调用；动态 import 防循环依赖 request -> auth） */
  async function fetchPermissions(): Promise<void> {
    if (!token.value) return
    try {
      const { permissionApi } = await import('@/api/permission')
      perms.value = await permissionApi.getMyPermissions()
    } catch {
      perms.value = null
    }
  }

  /** 权限矩阵：检查当前角色对某模块是否有某操作权限（无缓存/未配置视为无权限） */
  function hasPermission(module: string, action: 'view' | 'edit' | 'delete'): boolean {
    const p = perms.value?.[module]
    if (!p) return false
    return action === 'view' ? p.canView : action === 'edit' ? p.canEdit : p.canDelete
  }

  return {
    token,
    user,
    isLoggedIn,
    role,
    perms,
    login,
    setToken,
    setUser,
    logout,
    fetchPermissions,
    hasPermission,
  }
})
