import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 本地开发：前端 5173 → 网关 8000（/api 转发 REST，/ws 转发 WebSocket 升级请求）
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    host: '127.0.0.1',
    port: 5173,
    proxy: {
      // 模拟设备直连 energy-mock-device 8119：dev 环境直连（须在 /api 通用代理之前，避免被网关吞掉）
      '/api/mock': {
        target: 'http://127.0.0.1:8119',
        changeOrigin: true,
      },
      // OTA 直连 energy-ota 8118：旧 gateway 进程（无 /api/ota 路由）沙箱无法终止，
      // dev 环境先行直连绕过；生产环境 gateway 重启后自动走 /api/ota/** 网关路由
      '/api/ota': {
        target: 'http://127.0.0.1:8118',
        changeOrigin: true,
      },
      '/api': {
        target: 'http://127.0.0.1:8000',
        changeOrigin: true,
      },
      '/ws': {
        target: 'ws://127.0.0.1:8000',
        ws: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    chunkSizeWarningLimit: 1500,
    rollupOptions: {
      output: {
        manualChunks: {
          echarts: ['echarts'],
          'element-plus': ['element-plus'],
        },
      },
    },
  },
})
