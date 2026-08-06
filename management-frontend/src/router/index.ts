import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/auth/LoginView.vue'),
    meta: { public: true, title: '登录' },
  },
  {
    path: '/register',
    name: 'register',
    component: () => import('@/views/auth/RegisterView.vue'),
    meta: { public: true, title: '学生注册' },
  },
  {
    path: '/mentor-register',
    name: 'mentor-register',
    component: () => import('@/views/auth/MentorRegisterView.vue'),
    meta: { public: true, title: '导师注册' },
  },
  {
    path: '/admin-register',
    name: 'admin-register',
    component: () => import('@/views/auth/AdminRegisterView.vue'),
    meta: { public: true, title: '管理员注册' },
  },
  {
    path: '/nas-register',
    name: 'nas-register',
    component: () => import('@/views/auth/NasRegisterView.vue'),
    meta: { public: true, title: 'NAS 用户注册' },
  },
  {
    path: '/',
    component: () => import('@/layouts/BasicLayout.vue'),
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'dashboard',
        component: () => import('@/views/DashboardView.vue'),
        meta: { title: '首页' },
      },
      // 物理实例（管理+状态合并）
      {
        path: 'instances',
        name: 'instances',
        component: () => import('@/views/instance/InstanceView.vue'),
        meta: { title: '物理实例', module: 'physical-instance' },
      },
      // 容器
      {
        path: 'containers',
        name: 'containers',
        component: () => import('@/views/container/ContainerListView.vue'),
        meta: { title: '容器配置', module: 'container' },
      },
      // 镜像
      {
        path: 'images',
        name: 'images',
        component: () => import('@/views/image/ImageListView.vue'),
        meta: { title: '镜像管理', module: 'image' },
      },
      // 存储池
      {
        path: 'storage-pools',
        name: 'storage-pools',
        component: () => import('@/views/storage/StoragePoolListView.vue'),
        meta: { title: '存储池', module: 'storage-pool' },
      },
      // 工单
      {
        path: 'tickets',
        name: 'tickets',
        component: () => import('@/views/ticket/TicketListView.vue'),
        meta: { title: '特需工单', module: 'ticket' },
      },
      {
        path: 'tickets/manage',
        name: 'ticket-manage',
        component: () => import('@/views/ticket/TicketManageView.vue'),
        meta: { title: '工单管理', module: 'ticket', roles: ['ADMIN'] },
      },
      // 课题组（platform-refinements #4：管理员亦可见，做课题组增删改查）
      {
        path: 'group',
        name: 'group-info',
        component: () => import('@/views/group/GroupInfoView.vue'),
        meta: { title: '课题组信息', module: 'group', roles: ['MENTOR', 'STUDENT', 'ADMIN'] },
      },
      {
        path: 'group/allocation',
        name: 'group-allocation',
        component: () => import('@/views/group/StudentAllocationView.vue'),
        meta: { title: '学生资源分配', module: 'group', roles: ['MENTOR', 'ADMIN'] },
      },
      // 用户与权限（管理员）
      {
        path: 'admin/users',
        name: 'admin-users',
        component: () => import('@/views/admin/UserManageView.vue'),
        meta: { title: '用户与课题组管理', roles: ['ADMIN'] },
      },
      // 导师邀请注册（管理员创建导师邀请链接）
      {
        path: 'admin/mentor-invite',
        name: 'admin-mentor-invite',
        component: () => import('@/views/admin/MentorInviteView.vue'),
        meta: { title: '导师邀请注册', roles: ['ADMIN'] },
      },
      // 管理员注册邀请（管理员创建 ADMIN 邀请链接，被邀请人注册为管理员）
      {
        path: 'admin/admin-invite',
        name: 'admin-admin-invite',
        component: () => import('@/views/admin/AdminInviteView.vue'),
        meta: { title: '管理员注册邀请', roles: ['ADMIN'] },
      },
      // nas-allocation：管理员后台「NAS分配」模块（邀请管理 + 审批 + 已开通用户）
      {
        path: 'admin/nas-allocation',
        name: 'admin-nas-allocation',
        component: () => import('@/views/admin/NasAllocationView.vue'),
        meta: { title: 'NAS分配', roles: ['ADMIN'] },
      },
      // platform-refinements 11.5：受控端管理密码统一设置入口
      {
        path: 'admin/local-admin-password',
        name: 'admin-local-admin-password',
        component: () => import('@/views/admin/LocalAdminPasswordView.vue'),
        meta: { title: '受控端管理密码', roles: ['ADMIN'] },
      },
      {
        path: 'admin/permissions',
        name: 'admin-permissions',
        component: () => import('@/views/admin/PermissionMatrixView.vue'),
        meta: { title: '权限矩阵配置', roles: ['ADMIN'] },
      },
      // platform-env-ota-realtime D4/D7：受控端环境文件 + OTA 升级
      {
        path: 'admin/env-files',
        name: 'admin-env-files',
        component: () => import('@/views/admin/EnvFilesView.vue'),
        meta: { title: '受控端环境', roles: ['ADMIN'] },
      },
      {
        path: 'admin/agent-upgrade',
        name: 'admin-agent-upgrade',
        component: () => import('@/views/admin/AgentUpgradeView.vue'),
        meta: { title: '受控端升级', roles: ['ADMIN'] },
      },
      // 公告
      {
        path: 'announcements',
        name: 'announcements',
        component: () => import('@/views/announcement/AnnouncementListView.vue'),
        meta: { title: '公告', module: 'announcement' },
      },
      {
        path: 'announcements/manage',
        name: 'announcement-manage',
        component: () => import('@/views/announcement/AnnouncementManageView.vue'),
        meta: { title: '公告管理', roles: ['ADMIN'] },
      },
      // platform-audit-logging-ux：审计日志（三角色可见性由后端按 mentorIdAtOp 快照过滤）
      {
        path: 'audit-logs',
        name: 'audit-logs',
        component: () => import('@/views/admin/AuditLogView.vue'),
        meta: { title: '操作审计' },
      },
      // 受控端日志查看（管理员）
      {
        path: 'admin/agent-logs',
        name: 'admin-agent-logs',
        component: () => import('@/views/admin/AgentLogView.vue'),
        meta: { title: '受控端日志', roles: ['ADMIN'] },
      },
      // 审计开关（管理员）
      {
        path: 'admin/audit-switch',
        name: 'admin-audit-switch',
        component: () => import('@/views/admin/AuditSwitchView.vue'),
        meta: { title: '审计开关', roles: ['ADMIN'] },
      },
      // 用户信息
      {
        path: 'profile',
        name: 'profile',
        component: () => import('@/views/ProfileView.vue'),
        meta: { title: '用户信息' },
      },
      // 系统信息（platform-refinements #5：管理员可编辑，其他只读）
      {
        path: 'system-info',
        name: 'system-info',
        component: () => import('@/views/admin/SystemInfoView.vue'),
        meta: { title: '系统信息' },
      },
    ],
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/views/NotFoundView.vue'),
    meta: { public: true },
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach((to) => {
  const auth = useAuthStore()
  if (to.meta.public) return true

  if (!auth.isLoggedIn) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }

  const requiredRoles = to.meta.roles as string[] | undefined
  if (requiredRoles && auth.role && !requiredRoles.includes(auth.role)) {
    return { name: 'dashboard' }
  }

  if (to.meta.title) {
    document.title = `${to.meta.title} - 合算 Nexcompute`
  }
  return true
})

export default router
