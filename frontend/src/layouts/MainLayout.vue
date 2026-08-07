<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import { ElMessageBox } from 'element-plus'
import { useAlarmStore } from '@/stores/alarm'
import { useAuthStore } from '@/stores/auth'

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

const menus = [
  { path: '/dashboard', title: '设备监控', icon: 'Odometer' },
  { path: '/shadow', title: '影子', icon: 'Files' },
  { path: '/command', title: '指令中心', icon: 'Promotion' },
  { path: '/alarm', title: '告警中心', icon: 'Bell' },
  { path: '/ems/strategy', title: '策略管理', icon: 'SetUp' },
  { path: '/ems/plan', title: '充放电计划', icon: 'TrendCharts' },
]

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
        <span class="logo-text">三多能源 EMS</span>
      </div>
      <el-menu :default-active="route.path" router class="menu" background-color="#001529" text-color="#a6adb4" active-text-color="#fff">
        <el-menu-item v-for="m in menus" :key="m.path" :index="m.path">
          <span>{{ m.title }}</span>
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="header">
        <div class="header-title">{{ route.meta.title || '' }}</div>
        <div class="header-right">
          <el-tag :type="connected ? 'success' : 'danger'" size="small" effect="dark" class="ws-tag">
            <span class="dot" :class="{ on: connected }"></span>
            {{ connected ? '告警推送已连接' : '告警推送断开' }}
          </el-tag>
          <el-badge :value="unread" :hidden="unread === 0" :max="99">
            <span class="bell">🔔</span>
          </el-badge>
          <el-dropdown>
            <span class="user">
              <el-avatar :size="26" class="avatar">{{ avatarText }}</el-avatar>
              <span class="username">{{ displayName }}</span>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item disabled>深圳三多能源</el-dropdown-item>
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
.aside {
  background-color: #001529;
}
.logo {
  height: 60px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-size: 16px;
  font-weight: 600;
  letter-spacing: 1px;
}
.menu {
  border-right: none;
}
.header {
  background: #fff;
  border-bottom: 1px solid #e8e8e8;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
  height: 60px;
}
.header-title {
  font-size: 16px;
  font-weight: 600;
  color: #303133;
}
.header-right {
  display: flex;
  align-items: center;
  gap: 16px;
}
.ws-tag .dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #f56c6c;
  margin-right: 6px;
}
.ws-tag .dot.on {
  background: #67c23a;
}
.bell {
  color: #606266;
  cursor: pointer;
  font-size: 18px;
}
.user {
  display: flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
}
.avatar {
  background: #409eff;
}
.username {
  color: #606266;
  font-size: 14px;
}
.main {
  padding: 12px;
  overflow-y: auto;
}
</style>
