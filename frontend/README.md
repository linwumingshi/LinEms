# 三多能源 EMS 前端

Vue3 + TypeScript + Vite + Pinia + Element Plus + ECharts 驾驶舱。详见 `docs/design/Phase7-前端开发.md`。

## 环境

- Node ≥ 20（本机 v22.20.0）
- 后端依赖：Nacos(8848)、energy-gateway(8000)、energy-shadow/command/alarm 服务已启动

## 命令

```bash
npm install        # 安装依赖（registry 已指向 npmmirror）
npm run dev        # 开发：http://127.0.0.1:5173（/api、/ws 代理到网关 8000）
npm run build      # 类型检查 + 生产构建 → dist/
npm run preview    # 预览构建产物
npm test           # Vitest 单测（31 用例）
```

## 目录速览

- `src/api/`：Axios 封装（`http.ts` 统一解包 `Result<T>`）+ 各业务 API
- `src/ws/alarmSocket.ts`：`/ws/alarm` WebSocket 客户端（重连/心跳/结构校验）
- `src/stores/alarm.ts`：实时告警全局状态（未读/连接/事件流）
- `src/views/`：设备监控 / 影子 / 指令中心 / 告警中心
- `src/utils/alarmFormat.ts`：格式化与驾驶舱聚合纯函数
