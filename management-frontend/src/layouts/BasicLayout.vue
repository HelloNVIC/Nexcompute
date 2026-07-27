<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { Modal } from 'ant-design-vue'
import {
  DashboardOutlined,
  DesktopOutlined,
  AppstoreOutlined,
  PictureOutlined,
  DatabaseOutlined,
  FormOutlined,
  TeamOutlined,
  SafetyCertificateOutlined,
  NotificationOutlined,
  UserOutlined,
  UserAddOutlined,
  LogoutOutlined,
} from '@ant-design/icons-vue'
import { useAuthStore, type UserRole } from '@/stores/auth'
import { closeSseClient } from '@/utils/sse'
import NotificationInbox from '@/components/NotificationInbox.vue'
import RealtimeToast from '@/components/RealtimeToast.vue'
import AnnouncementLoginModal from '@/components/AnnouncementLoginModal.vue'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const collapsed = ref(false)

interface MenuItem {
  key: string
  label: string
  icon?: unknown
  roles?: UserRole[]
  children?: MenuItem[]
}

const menus = computed<MenuItem[]>(() => {
  const role = auth.user?.role
  const all: MenuItem[] = [
    { key: '/dashboard', label: '首页', icon: DashboardOutlined },
    { key: '/instances', label: '物理实例', icon: DesktopOutlined },
    { key: '/containers', label: '容器配置', icon: AppstoreOutlined },
    { key: '/images', label: '镜像管理', icon: PictureOutlined },
    { key: '/storage-pools', label: '存储池', icon: DatabaseOutlined },
    { key: '/tickets', label: '特需工单', icon: FormOutlined, roles: ['STUDENT', 'MENTOR'] },
    { key: '/tickets/manage', label: '工单管理', icon: FormOutlined, roles: ['ADMIN'] },
    // platform-refinements #4：管理员亦可见课题组（增删改查 + 成员管理）
    { key: '/group', label: role === 'ADMIN' ? '课题组管理' : '课题组信息', icon: TeamOutlined, roles: ['MENTOR', 'STUDENT', 'ADMIN'] },
    // platform-refinements 8.4：菜单标题按角色——MENTOR=学生资源分配、ADMIN=课题组资源分配
    { key: '/group/allocation', label: role === 'ADMIN' ? '课题组资源分配' : '学生资源分配', icon: TeamOutlined, roles: ['MENTOR', 'ADMIN'] },
    { key: '/admin/users', label: '用户与课题组管理', icon: UserOutlined, roles: ['ADMIN'] },
    { key: '/admin/mentor-invite', label: '导师邀请注册', icon: UserAddOutlined, roles: ['ADMIN'] },
    // platform-refinements 11.5：受控端管理密码统一设置入口
    { key: '/admin/local-admin-password', label: '受控端管理密码', icon: SafetyCertificateOutlined, roles: ['ADMIN'] },
    { key: '/admin/permissions', label: '权限矩阵配置', icon: SafetyCertificateOutlined, roles: ['ADMIN'] },
    // platform-env-ota-realtime D4/D7：受控端环境文件 + OTA 升级
    { key: '/admin/env-files', label: '受控端环境', icon: DesktopOutlined, roles: ['ADMIN'] },
    { key: '/admin/agent-upgrade', label: '受控端升级', icon: DesktopOutlined, roles: ['ADMIN'] },
    { key: '/admin/agent-logs', label: '受控端日志', icon: DesktopOutlined, roles: ['ADMIN'] },
    { key: '/admin/audit-switch', label: '审计开关', icon: SafetyCertificateOutlined, roles: ['ADMIN'] },
    // platform-audit-logging-ux：审计日志三角色可见（后端按 mentorIdAtOp 快照过滤）
    { key: '/audit-logs', label: '操作审计', icon: SafetyCertificateOutlined },
    { key: '/announcements', label: '公告', icon: NotificationOutlined, roles: ['STUDENT', 'MENTOR'] },
    { key: '/announcements/manage', label: '公告管理', icon: NotificationOutlined, roles: ['ADMIN'] },
    { key: '/profile', label: '用户信息', icon: UserOutlined },
    { key: '/system-info', label: '系统信息', icon: SafetyCertificateOutlined },
  ]
  return all.filter((m) => !m.roles || (role && m.roles.includes(role)))
})

const selectedKeys = computed(() => {
  const path = route.path
  // 精确匹配优先，否则取最长前缀
  const exact = menus.value.find((m) => m.key === path)
  if (exact) return [path]
  const prefix = menus.value
    .filter((m) => path.startsWith(m.key))
    .sort((a, b) => b.key.length - a.key.length)[0]
  return prefix ? [prefix.key] : ['/dashboard']
})

function navigate(key: string): void {
  router.push(key)
}

function onMenuClick(info: { key: string }): void {
  navigate(info.key)
}

function confirmLogout(): void {
  Modal.confirm({
    title: '确认退出登录？',
    onOk: () => {
      auth.logout()
      closeSseClient()
      router.push('/login')
    },
  })
}

const roleLabel: Record<UserRole, string> = {
  ADMIN: '管理员',
  MENTOR: '导师',
  STUDENT: '学生',
}
</script>

<template>
  <a-layout style="min-height: 100vh">
    <a-layout-sider v-model:collapsed="collapsed" collapsible>
      <div class="logo">
        <img src="/favicon.svg" class="logo-icon" alt="Nexcompute" />
        <span v-if="!collapsed" class="logo-text">合算 Nexcompute</span>
      </div>
      <a-menu theme="dark" mode="inline" :selected-keys="selectedKeys" @click="onMenuClick">
        <a-menu-item v-for="m in menus" :key="m.key">
          <component :is="m.icon" />
          <span>{{ m.label }}</span>
        </a-menu-item>
      </a-menu>
    </a-layout-sider>

    <a-layout>
      <a-layout-header class="header">
        <div class="header-left">
          <a-button type="text" @click="collapsed = !collapsed">≡</a-button>
        </div>
        <div class="header-right">
          <NotificationInbox />
          <a-dropdown>
            <a-button type="text">
              <UserOutlined />
              <span style="margin-left: 6px">{{ auth.user?.realName ?? auth.user?.username }}</span>
              <a-tag color="blue" style="margin-left: 8px">{{ roleLabel[auth.user!.role] }}</a-tag>
            </a-button>
            <template #overlay>
              <a-menu>
                <a-menu-item @click="router.push('/profile')">
                  <UserOutlined /> 用户信息
                </a-menu-item>
                <a-menu-divider />
                <a-menu-item @click="confirmLogout">
                  <LogoutOutlined /> 退出登录
                </a-menu-item>
              </a-menu>
            </template>
          </a-dropdown>
        </div>
      </a-layout-header>
      <a-layout-content class="content">
        <RouterView />
      </a-layout-content>
    </a-layout>
    <!-- platform-env-ota-realtime D8：右下角实时变动 Toast（订阅 SSE，无 UI） -->
    <RealtimeToast />
    <!-- D9：导师/管理员登录公告中央弹窗 -->
    <AnnouncementLoginModal />
  </a-layout>
</template>

<style scoped>
.logo {
  height: 48px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 0 12px;
  color: #fff;
  font-size: 16px;
  font-weight: 600;
  background: rgba(255, 255, 255, 0.08);
}
.logo-icon {
  width: 28px;
  height: 28px;
  flex-shrink: 0;
}
.logo-text {
  white-space: nowrap;
  overflow: hidden;
}
.header {
  background: #fff;
  padding: 0 16px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  box-shadow: 0 1px 4px rgba(0, 21, 41, 0.08);
}
.header-right {
  display: flex;
  align-items: center;
  gap: 16px;
}
.content {
  margin: 16px;
  padding: 24px;
  background: #fff;
  border-radius: 8px;
  min-height: 280px;
}
</style>
