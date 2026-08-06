<script setup lang="ts">
// 根组件：仅承载路由出口，初始化告警 WebSocket 连接
import { onMounted } from 'vue'
import { useAlarmStore } from '@/stores/alarm'

const alarmStore = useAlarmStore()

onMounted(() => {
  // 全局常驻实时告警通道（断线自动重连）
  alarmStore.initSocket()
})
</script>

<template>
  <router-view />
</template>
