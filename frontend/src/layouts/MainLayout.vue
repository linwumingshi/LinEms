<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import { ElMessageBox } from 'element-plus'
import { useAlarmStore } from '@/stores/alarm'
import { useAuthStore } from '@/stores/auth'
import { hasPermi } from '@/utils/permission'

const route = useRoute()
const router = useRouter()
const alarmStore = useAlarmStore()
const authStore = useAuthStore()
const { unread, connected } = storeToRefs(alarmStore)

/** 右上角显示名：realName 优先，回退登录名 */
const displayName = computed(
  () => authStore.user?.realName || authStore.user?.username || '运维值班',
)
/** 头像首字 */
const avatarText = computed(() => displayName.value.slice(0, 1))

interface MenuItem {
  path?: string
  title: string
  perms?: string[]
  group?: string
  children?: MenuItem[]
}

const menus: MenuItem[] = [
  { path: '/dashboard', title: '设备监控' },
  {
    group: '/device',
    title: '设备资产',
    children: [
      { path: '/product', title: '产品管理' },
      { path: '/device', title: '设备管理' },
    ],
  },
  {
    group: '/operation',
    title: '设备运维',
    children: [
      { path: '/shadow', title: '影子' },
      { path: '/command', title: '指令中心' },
      { path: '/alarm', title: '告警中心' },
    ],
  },
  {
    group: '/ems',
    title: 'EMS 能源管理',
    children: [
      { path: '/ems/strategy', title: '策略管理' },
      { path: '/ems/constraint', title: '安全约束' },
      { path: '/ems/plan', title: '充放电计划' },
    ],
  },
  {
    group: '/archive',
    title: '基础档案',
    children: [
      { path: '/archive/enterprise', title: '单位管理', perms: ['system:enterprise:list'] },
      { path: '/archive/station', title: '电站管理' },
    ],
  },
  {
    group: '/system',
    title: '系统管理',
    children: [
      { path: '/system/user', title: '用户管理', perms: ['system:user:list'] },
      { path: '/system/role', title: '角色管理', perms: ['system:role:list'] },
      { path: '/system/perm', title: '菜单权限', perms: ['system:perm:list'] },
    ],
  },
]

/** 按当前用户权限过滤后的可见菜单：先过滤各组子项，再剔除空组与无权限的普通项 */
const visibleMenus = computed(() =>
  menus
    .map((m) => (m.children
      ? { ...m, children: m.children.filter((c) => hasPermi(authStore.permissions, c.perms ?? [])) }
      : m))
    .filter((m) => (m.children ? m.children.length > 0 : hasPermi(authStore.permissions, m.perms ?? []))),
)

/** 登出：关 WS → 吊销会话（后端失败也清本地）→ 回登录页 */
async function handleLogout(): Promise<void> {
  try {
    await ElMessageBox.confirm('确定退出登录吗？', '提示', {
      type: 'warning',
      confirmButtonText: '退出',
      cancelButtonText: '取消',
    })
  } catch {
    return // 取消
  }
  alarmStore.closeSocket()
  await authStore.logout()
  await router.push('/login')
}
</script>

<template>
  <el-container class="layout">
    <el-aside width="220px" class="aside">
      <div class="logo">
        <svg class="logo-mark" viewBox="0 0 24 24" aria-hidden="true">
          <path
            d="M13.5 2.2 5 13h5.2l-1.6 8.8L17.5 11h-5.3L13.5 2.2z"
            fill="currentColor"
          />
        </svg>
        <span class="logo-text">EnergyX</span>
      </div>
      <el-menu :default-active="route.path" router class="menu" :default-openeds="['/system']">
        <template v-for="m in visibleMenus" :key="m.path ?? m.group">
          <el-sub-menu v-if="m.children && m.children.length" :index="m.group ?? m.title">
            <template #title><span>{{ m.title }}</span></template>
            <el-menu-item v-for="c in m.children" :key="c.path" :index="c.path!">
              <span>{{ c.title }}</span>
            </el-menu-item>
          </el-sub-menu>
          <el-menu-item v-else :index="m.path!">
            <span>{{ m.title }}</span>
          </el-menu-item>
        </template>
      </el-menu>
      <div class="aside-foot">
        <span class="foot-dot" :class="{ on: connected }"></span>
        监控通道 {{ connected ? '在线' : '离线' }}
      </div>
    </el-aside>

    <el-container>
      <el-header class="header">
        <div class="header-title">{{ route.meta.title || '' }}</div>
        <div class="header-right">
          <span class="ws-pill" :class="{ on: connected }">
            <span class="dot"></span>
            {{ connected ? '告警推送已连接' : '告警推送断开' }}
          </span>
          <el-badge :value="unread" :hidden="unread === 0" :max="99" class="bell-badge">
            <span class="bell" role="img" aria-label="未读告警">🔔</span>
          </el-badge>
          <el-dropdown>
            <span class="user">
              <el-avatar :size="26" class="avatar">{{ avatarText }}</el-avatar>
              <span class="username">{{ displayName }}</span>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item disabled>EnergyX</el-dropdown-item>
                <el-dropdown-item divided @click="handleLogout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.layout {
  height: 100%;
}

/* —— 左侧仪表导航：纸面底 + 发丝右线 + 充电绿激活标记 —— */
.aside {
  background: var(--ex-card);
  border-right: 1px solid var(--ex-hair);
  display: flex;
  flex-direction: column;
}
.logo {
  height: 60px;
  padding: 0 20px;
  display: flex;
  align-items: center;
  gap: 9px;
  border-bottom: 1px solid var(--ex-hair-soft);
}
.logo-mark {
  width: 20px;
  height: 20px;
  color: var(--ex-charge);
  flex: none;
}
.logo-text {
  font-family: 'Bahnschrift', 'DIN Alternate', 'Segoe UI', sans-serif;
  font-size: 17px;
  font-weight: 700;
  letter-spacing: 0.6px;
  color: var(--ex-ink);
}
.menu {
  flex: 1;
  border-right: none;
  padding: 8px 0;
  --el-menu-bg-color: transparent;
  --el-menu-text-color: var(--ex-ink-2);
  --el-menu-active-color: var(--ex-ink);
  --el-menu-hover-bg-color: var(--ex-bg);
  --el-menu-hover-text-color: var(--ex-ink);
}
.menu :deep(.el-menu-item) {
  margin: 2px 10px;
  border-radius: 5px;
  font-size: 14px;
  height: 40px;
  line-height: 40px;
  position: relative;
}
.menu :deep(.el-menu-item.is-active) {
  background: #e9f3ee;
  font-weight: 600;
}
.menu :deep(.el-menu-item.is-active)::before {
  content: '';
  position: absolute;
  left: 0;
  top: 9px;
  bottom: 9px;
  width: 3px;
  border-radius: 0 2px 2px 0;
  background: var(--ex-charge);
}
.menu :deep(.el-sub-menu__title) {
  margin: 2px 10px;
  border-radius: 5px;
  font-size: 14px;
  height: 40px;
  line-height: 40px;
  color: var(--ex-ink-2);
}
.aside-foot {
  padding: 14px 20px;
  font-size: 12px;
  color: var(--ex-ink-3);
  border-top: 1px solid var(--ex-hair-soft);
  display: flex;
  align-items: center;
  gap: 6px;
  font-variant-numeric: tabular-nums;
}
.foot-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--ex-ink-3);
}
.foot-dot.on {
  background: var(--ex-charge);
}

/* —— 顶栏：纸面 + 发丝底线 —— */
.header {
  background: var(--ex-card);
  border-bottom: 1px solid var(--ex-hair);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
  height: 60px;
}
.header-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--ex-ink);
  letter-spacing: 0.3px;
}
.header-right {
  display: flex;
  align-items: center;
  gap: 18px;
}
.ws-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--ex-ink-2);
  border: 1px solid var(--ex-hair);
  border-radius: 999px;
  padding: 3px 10px;
  font-variant-numeric: tabular-nums;
}
.ws-pill .dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--ex-danger);
}
.ws-pill.on .dot {
  background: var(--ex-charge);
}
.ws-pill.on {
  color: var(--ex-charge);
  border-color: #cfe6d8;
  background: #f2f9f5;
}
.bell {
  color: var(--ex-ink-2);
  cursor: pointer;
  font-size: 18px;
  line-height: 1;
  display: inline-block;
  padding: 2px;
}
.user {
  display: flex;
  align-items: center;
  gap: 7px;
  cursor: pointer;
}
.avatar {
  background: var(--ex-steel);
  font-size: 12px;
}
.username {
  color: var(--ex-ink-2);
  font-size: 13px;
}

/* —— 主区：仪器纸面 —— */
.main {
  padding: 16px;
  overflow-y: auto;
  background: var(--ex-bg);
}
</style>
