import { http } from '@/utils/request'

// 权限矩阵 API（任务 2.3）
export interface Perm {
  canView: boolean
  canEdit: boolean
  canDelete: boolean
}

export interface PermissionMatrixItem {
  moduleCode: string
  moduleName: string
  permissions: Record<string, Perm> // role -> perm
}

export const permissionApi = {
  getMatrix: () => http.get<PermissionMatrixItem[]>('/admin/permissions'),
  update: (data: { role: string; moduleCode: string; canView: boolean; canEdit: boolean; canDelete: boolean }) =>
    http.put('/admin/permissions', data),
  // D13：恢复默认权限矩阵
  resetDefault: () => http.post('/admin/permissions/reset-default'),
  // 用户信息必填项配置（platform-refinements #5）
  getFieldConfig: () =>
    http.get<{ realName: boolean; studentId: boolean; email: boolean; phone: boolean; groupId: boolean }>(
      '/admin/permissions/user-field-config',
    ),
  updateFieldConfig: (data: Partial<{ realName: boolean; studentId: boolean; email: boolean; phone: boolean; groupId: boolean }>) =>
    http.put('/admin/permissions/user-field-config', data),
}
