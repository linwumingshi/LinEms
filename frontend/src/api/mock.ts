import http from '@/api/http'

/** 模拟设备快照（对齐后端 SimDeviceView） */
export interface SimDeviceView {
	simId: string
	productKey: string
	deviceName: string
	deviceId: number | null
	autoProvisioned: boolean
	connected: boolean
	online: boolean
	recentLogs: Array<Record<string, unknown>>
	pendingCommands: Array<Record<string, unknown>>
}

/** 模拟设备 REST 控制面封装（前端经 vite 代理直连 energy-mock-device:8119） */
export const mockApi = {
	/** GET /api/mock/devices 拉取所有模拟设备快照 */
	list(): Promise<SimDeviceView[]> {
		return http.get('/api/mock/devices')
	},
	/** POST /api/mock/devices 创建模拟设备（auto=自动建档 / takeover=接管已有） */
	create(payload: Record<string, unknown>): Promise<SimDeviceView> {
		return http.post('/api/mock/devices', payload)
	},
	/** POST /api/mock/devices/{simId}/start 上线（建链并订阅） */
	start(simId: string): Promise<SimDeviceView> {
		return http.post(`/api/mock/devices/${encodeURIComponent(simId)}/start`)
	},
	/** POST /api/mock/devices/{simId}/stop 下线（断链） */
	stop(simId: string): Promise<SimDeviceView> {
		return http.post(`/api/mock/devices/${encodeURIComponent(simId)}/stop`)
	},
	/** DELETE /api/mock/devices/{simId} 销毁模拟设备 */
	remove(simId: string): Promise<void> {
		return http.delete(`/api/mock/devices/${encodeURIComponent(simId)}`)
	},
	/** POST /api/mock/devices/{simId}/report 上报属性/事件 */
	report(simId: string, type: string, json: string): Promise<void> {
		return http.post(`/api/mock/devices/${encodeURIComponent(simId)}/report`, { type, json })
	},
	/** POST /api/mock/devices/{simId}/lifecycle 生命周期上下线 */
	lifecycle(simId: string, online: boolean): Promise<void> {
		return http.post(`/api/mock/devices/${encodeURIComponent(simId)}/lifecycle`, { online })
	},
	/** POST /api/mock/devices/{simId}/ack 应答下行指令（auto=自动成功应答） */
	ack(simId: string, commandId: string, status: string, result: string): Promise<void> {
		return http.post(`/api/mock/devices/${encodeURIComponent(simId)}/ack`, { commandId, status, result })
	},
}

/** WebSocket 事件（服务端→前端广播） */
export interface MockWsEvent {
	type: 'command' | 'ota-down' | 'ota-progress' | 'ota-result' | 'ota-inform' | 'ack' | 'down' | 'log'
	simId: string
	deviceName: string
	ts: number
	topic: string
	payload: unknown
}

/**
 * 连接模拟设备 WebSocket（/ws/mock），vite 开发代理转发至 ws://127.0.0.1:8119。
 * onMessage 收到已解析的事件对象。
 */
export function connectMockWs(onMessage: (ev: MockWsEvent) => void): WebSocket {
	const proto = location.protocol === 'https:' ? 'wss' : 'ws'
	const url = `${proto}://${location.host}/ws/mock`
	const ws = new WebSocket(url)
	ws.onmessage = (e) => {
		try {
			onMessage(JSON.parse(e.data) as MockWsEvent)
		}
		catch {
			// 忽略非 JSON 帧
		}
	}
	return ws
}
