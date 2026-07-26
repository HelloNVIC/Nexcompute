import { http } from '@/utils/request'

export interface RegistrationLink {
  id: number
  token: string
  groupId: number
  creatorId: number
  remainingCount: number
  expireAt: string
  status: string
  createdAt: string
}

export interface MachineAllocation {
  id: number
  instanceId: number
  userId: number
  groupId?: number
  allocatedBy: number
  allocatedAt: string
  /** 单容器内存上限（MB，可空=不限，platform-refinements 8.2） */
  perContainerMemoryMb?: number
  /** 被分配学生姓名（platform-refinements #2） */
  studentName?: string
}

export const allocationApi = {
  createLink: (data: { remainingCount: number; expireAt: string }) =>
    http.post<RegistrationLink>('/allocations/registration-links', data),
  revokeLink: (token: string) => http.post(`/allocations/registration-links/${token}/revoke`),
  myLinks: () => http.get<RegistrationLink[]>('/allocations/registration-links'),
  /** 导师分配机器给学生（platform-refinements 8.2：含单容器内存上限） */
  allocate: (data: {
    instanceId: number
    studentId: number
    groupId?: number
    perContainerMemoryMb?: number
  }) => http.post<MachineAllocation>('/allocations/machines', data),
  /** 管理员按课题组分配物理实例（platform-refinements 8.1） */
  allocateGroup: (data: { instanceId: number; groupId: number }) =>
    http.post<MachineAllocation[]>('/allocations/machines/group', data),
  /** 课题组已分配资源列表（platform-refinements #4） */
  groupAllocations: () =>
    http.get<Array<{ groupId: number; groupName: string; instanceId: number; instanceNumber: string; machineName?: string; allocatedAt: string }>>(
      '/allocations/groups',
    ),
  deallocate: (id: number) => http.delete(`/allocations/machines/${id}`),
  /** 撤销分配影响检查（platform-refinements #3） */
  impact: (id: number) =>
    http.get<{ studentName: string; instanceId: number; runningContainers: Array<{ id: number; name: string }>; storagePools: Array<{ id: number; poolName: string }> }>(
      `/allocations/machines/${id}/impact`,
    ),
  /** 撤销课题组在指定实例上的分配影响检查 */
  groupImpact: (instanceId: number, groupId: number) =>
    http.get<{
      groupName: string
      instanceId: number
      memberCount: number
      runningContainers: Array<{ id: number; name: string; ownerId: number }>
      storagePools: Array<{ id: number; poolName: string; ownerId: number }>
    }>(`/allocations/groups/${instanceId}/${groupId}/impact`),
  /** 撤销课题组在指定实例上的分配 */
  deallocateGroup: (instanceId: number, groupId: number) =>
    http.delete<number>(`/allocations/groups/${instanceId}/${groupId}`),
  myMachines: () => http.get<MachineAllocation[]>('/allocations/machines/my'),
}
