import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'

import App from './App.vue'
import router from './router'
import { onUnauthorized } from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import './styles/main.css'

const app = createApp(App)

app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })

// 启动即恢复登录态（localStorage → store），供路由守卫 / 请求拦截器 / WS token 判断
const authStore = useAuthStore()
authStore.restoreFromStorage()

// HTTP 401（网关判定未认证/已过期）→ 回登录页并携带当前地址，供登录后回跳
onUnauthorized(() => {
  const current = router.currentRoute.value
  if (current.path !== '/login') {
    void router.push({ path: '/login', query: { redirect: current.fullPath } })
  }
})

app.mount('#app')
