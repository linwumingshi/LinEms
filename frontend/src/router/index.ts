import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { getToken } from '@/utils/auth-storage'

const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/Login.vue'),
    meta: { title: '登录', public: true },
  },
  {
    path: '/',
    component: () => import('@/layouts/MainLayout.vue'),
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('@/views/Dashboard.vue'),
        meta: { title: '设备监控', icon: 'Odometer' },
      },
      {
        path: 'shadow',
        name: 'Shadow',
        component: () => import('@/views/Shadow.vue'),
        meta: { title: '影子', icon: 'Files' },
      },
      {
        path: 'command',
        name: 'Command',
        component: () => import('@/views/Command.vue'),
        meta: { title: '指令中心', icon: 'Promotion' },
      },
      {
        path: 'alarm',
        name: 'Alarm',
        component: () => import('@/views/Alarm.vue'),
        meta: { title: '告警中心', icon: 'Bell' },
      },
    ],
  },
  {
    path: '/:pathMatch(.*)*',
    redirect: '/dashboard',
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

/**
 * 路由守卫核心判定（纯函数，便于单测）：
 * - 未登录访问非公开页 → '/login'（守卫附加 ?redirect=<fullPath> 供登录后回跳）；
 * - 已登录访问 /login → '/'（已登录无需再登）；
 * - 其余（未登录访问公开页 / 已登录访问业务页）→ null 放行。
 */
export function resolveAuthRedirect(
  to: { path: string; meta?: Record<string, unknown> },
  hasToken: boolean,
): string | null {
  const isPublic = to.meta?.public === true
  if (hasToken) {
    return to.path === '/login' ? '/' : null
  }
  return isPublic ? null : '/login'
}

router.beforeEach((to) => {
  const target = resolveAuthRedirect(to, getToken() !== null)
  if (target === null) return true
  if (to.path === '/login') {
    // 已登录访问登录页 → 回首页
    return { path: target }
  }
  // 未登录访问受保护页 → 登录页并携带回跳地址
  return { path: target, query: { redirect: to.fullPath } }
})

export default router
