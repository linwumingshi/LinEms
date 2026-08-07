<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import { useAlarmStore } from '@/stores/alarm'

const route = useRoute()
const router = useRouter()
const authStore = useAuthStore()
const alarmStore = useAlarmStore()

const formRef = ref<FormInstance>()
const loading = ref(false)
const model = reactive({ username: 'admin', password: 'admin123' })

const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

async function handleLogin(): Promise<void> {
  if (!formRef.value) return
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  loading.value = true
  try {
    await authStore.login({ username: model.username, password: model.password })
    // 重新拉起告警 WS（登出时已 closeSocket；App 不会重新挂载，onMounted 只跑一次）
    alarmStore.initSocket()
    // 登录后回跳守卫记录的原始地址（?redirect=），缺省回首页
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
    ElMessage.success('登录成功')
    await router.push(redirect)
  } catch (e) {
    // 登录失败是 HTTP 200 + 业务错误码，由 http 拦截器归一为 Error 抛到这里
    ElMessage.error(e instanceof Error ? e.message : String(e))
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="login-grid" aria-hidden="true"></div>

    <div class="login-card ex-card">
      <div class="brand">
        <span class="brand-row">
          <svg class="brand-mark" viewBox="0 0 24 24" aria-hidden="true">
            <path d="M13.5 2.2 5 13h5.2l-1.6 8.8L17.5 11h-5.3L13.5 2.2z" fill="currentColor" />
          </svg>
          <h1 class="brand-name">EnergyX</h1>
        </span>
        <p class="brand-sub">储能物联网监控与能量管理</p>
      </div>

      <el-form ref="formRef" :model="model" :rules="rules" size="large" @keyup.enter="handleLogin">
        <el-form-item prop="username">
          <el-input v-model="model.username" placeholder="用户名" clearable />
        </el-form-item>
        <el-form-item prop="password">
          <el-input v-model="model.password" type="password" placeholder="密码" show-password />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" class="submit" :loading="loading" @click="handleLogin">
            登 录
          </el-button>
        </el-form-item>
      </el-form>

      <div class="hint">演示账号：admin / admin123</div>
    </div>

    <div class="login-status" aria-hidden="true">
      <span class="pulse"></span>
      EnergyX 监控终端 · 系统服务就绪
    </div>
  </div>
</template>

<style scoped>
.login-page {
  height: 100%;
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--ex-bg);
  overflow: hidden;
}

/* 蓝图网格纸底纹：发丝线正交网格，仪表设计图纸的世界 */
.login-grid {
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(var(--ex-hair-soft) 1px, transparent 1px),
    linear-gradient(90deg, var(--ex-hair-soft) 1px, transparent 1px);
  background-size: 32px 32px;
  mask-image: radial-gradient(ellipse 70% 60% at 50% 42%, #000 30%, transparent 72%);
  -webkit-mask-image: radial-gradient(ellipse 70% 60% at 50% 42%, #000 30%, transparent 72%);
}

.login-card {
  width: 380px;
  padding: 34px 34px 22px;
  position: relative;
  z-index: 1;
  box-shadow: 0 12px 40px rgba(31, 40, 51, 0.08);
}
.login-card::before {
  content: '';
  position: absolute;
  left: -1px;
  top: -1px;
  width: calc(100% + 2px);
  height: 3px;
  border-radius: 6px 6px 0 0;
  background: linear-gradient(90deg, var(--ex-charge), var(--ex-steel));
}

.brand {
  text-align: center;
  margin-bottom: 24px;
}
.brand-row {
  display: inline-flex;
  align-items: center;
  gap: 9px;
}
.brand-mark {
  width: 26px;
  height: 26px;
  color: var(--ex-charge);
}
.brand-name {
  margin: 0;
  font-family: 'Bahnschrift', 'DIN Alternate', 'Segoe UI', sans-serif;
  font-size: 24px;
  font-weight: 700;
  letter-spacing: 1px;
  color: var(--ex-ink);
}
.brand-sub {
  margin: 8px 0 0;
  font-size: 13px;
  letter-spacing: 2px;
  color: var(--ex-ink-2);
}

.submit {
  width: 100%;
  font-weight: 600;
  letter-spacing: 6px;
}
.hint {
  text-align: center;
  font-size: 12px;
  color: var(--ex-ink-3);
  margin-top: 4px;
  font-variant-numeric: tabular-nums;
}

/* 登录页专属签名：控制室“系统就绪”脉搏灯 */
.login-status {
  position: absolute;
  bottom: 22px;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  letter-spacing: 1px;
  color: var(--ex-ink-3);
  z-index: 1;
}
.pulse {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--ex-charge);
  animation: breathe 2.4s ease-in-out infinite;
}
@keyframes breathe {
  0%,
  100% {
    box-shadow: 0 0 0 0 rgba(46, 158, 91, 0.4);
  }
  50% {
    box-shadow: 0 0 0 6px rgba(46, 158, 91, 0);
  }
}
</style>
