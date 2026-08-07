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
    <el-card class="login-card">
      <div class="brand">
        <h1>EnergyX</h1>
        <p>储能物联网监控与能量管理</p>
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
    </el-card>
  </div>
</template>

<style scoped>
.login-page {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #001529 0%, #0b3d6e 100%);
}
.login-card {
  width: 380px;
  padding: 8px 12px 4px;
}
.brand {
  text-align: center;
  margin-bottom: 20px;
}
.brand h1 {
  margin: 0 0 6px;
  font-size: 20px;
  color: #303133;
  letter-spacing: 2px;
}
.brand p {
  margin: 0;
  font-size: 13px;
  color: #909399;
}
.submit {
  width: 100%;
}
.hint {
  text-align: center;
  font-size: 12px;
  color: #909399;
}
</style>
