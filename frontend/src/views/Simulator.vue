<script setup lang="ts">
import { onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { mockApi, connectMockWs, type SimDeviceView, type MockWsEvent } from '@/api/mock'
import { productApi } from '@/api/product'
import { deviceApi } from '@/api/device'
import type { Device, Product } from '@/types/models'

const devices = ref<SimDeviceView[]>([])
const selected = ref<SimDeviceView | null>(null)
const loading = ref(false)

/** 新建模拟设备对话框 */
const createVisible = ref(false)
const createForm = reactive({
	mode: 'auto',
	productKey: '',
	deviceId: '',
	deviceName: '',
	deviceType: 'EDGE_GW',
	secret: '',
	firmwareVersion: '',
})
const products = ref<Product[]>([])
const productLoading = ref(false)
/** 选产品后加载该产品下已有设备，供“设备”下拉选择 */
const deviceOptions = ref<Device[]>([])
const deviceLoading = ref(false)

/** 属性/事件上报 */
const reportType = ref<'property' | 'event'>('property')
const reportJson = ref('{\n  "soc": 88.5,\n  "power": 5000\n}')

/** WS 实时事件（按 simId 分桶，ring buffer 100） */
const feed = reactive<Record<string, MockWsEvent[]>>({})

let ws: WebSocket | null = null
let timer: number | undefined

async function load() {
	loading.value = true
	try {
		devices.value = await mockApi.list()
		if (selected.value) {
			selected.value = devices.value.find((d) => d.simId === selected.value!.simId) || null
		}
	}
	catch (e: any) {
		// 模块未启动等情况静默，保留上一次列表
	}
	finally {
		loading.value = false
	}
}

function selectDevice(row: SimDeviceView) {
	selected.value = row
}

async function openCreate() {
	// 每次打开重置表单，避免上次残留影响“设备”下拉
	Object.assign(createForm, {
		mode: 'auto',
		productKey: '',
		deviceId: '',
		deviceName: '',
		deviceType: 'EDGE_GW',
		secret: '',
		firmwareVersion: '',
	})
	deviceOptions.value = []
	createVisible.value = true
	if (products.value.length === 0) {
		productLoading.value = true
		try {
			const r = await productApi.page({ pageNum: 1, pageSize: 200 })
			products.value = (r as any).records || []
		}
		catch {
			// 忽略
		}
		finally {
			productLoading.value = false
		}
	}
}

/** 选择产品后加载该产品下已有设备，供“设备”下拉选择；切换产品时清空已选设备 */
watch(
	() => createForm.productKey,
	async (pk) => {
		createForm.deviceId = ''
		createForm.deviceName = ''
		deviceOptions.value = []
		if (!pk) {
			return
		}
		deviceLoading.value = true
		try {
			const r = await deviceApi.page({ productKey: pk, pageNum: 1, pageSize: 200 })
			deviceOptions.value = (r as any).records || []
		}
		catch {
			// 忽略
		}
		finally {
			deviceLoading.value = false
		}
	},
)

/** 从“设备”下拉选中后，自动带入设备名（接管模式仍需填写 secret） */
function pickDevice(id: string) {
	const d = deviceOptions.value.find((x) => x.deviceId === id)
	createForm.deviceName = d?.deviceName || ''
}

async function submitCreate() {
	if (!createForm.productKey || !createForm.deviceName) {
		ElMessage.warning('请填写产品标识与设备名')
		return
	}
	if (createForm.mode === 'takeover' && !createForm.secret) {
		ElMessage.warning('接管模式需填写 secret')
		return
	}
	const payload: Record<string, unknown> = {
		mode: createForm.mode,
		productKey: createForm.productKey,
		deviceName: createForm.deviceName,
	}
	if (createForm.mode === 'auto') {
		payload.deviceType = createForm.deviceType
		if (createForm.firmwareVersion)
			payload.firmwareVersion = createForm.firmwareVersion
	}
	else {
		payload.secret = createForm.secret
	}
	try {
		await mockApi.create(payload)
		ElMessage.success('模拟设备已创建并上线')
		createVisible.value = false
		await load()
		if (devices.value.length)
			selectDevice(devices.value[devices.value.length - 1])
	}
	catch (e: any) {
		ElMessage.error(e?.message || '创建失败')
	}
}

async function doStart(s: SimDeviceView) {
	await mockApi.start(s.simId)
	ElMessage.success('已启动')
	await load()
}

async function doStop(s: SimDeviceView) {
	await mockApi.stop(s.simId)
	ElMessage.success('已停止')
	await load()
}

async function doRemove(s: SimDeviceView) {
	await mockApi.remove(s.simId)
	if (selected.value?.simId === s.simId)
		selected.value = null
	ElMessage.success('已删除')
	await load()
}

async function doReport() {
	if (!selected.value) {
		ElMessage.warning('请先选择设备')
		return
	}
	try {
		JSON.parse(reportJson.value)
	}
	catch {
		ElMessage.warning('上报 JSON 格式不正确')
		return
	}
	await mockApi.report(selected.value.simId, reportType.value, reportJson.value)
	ElMessage.success('已上报')
}

async function doOnline() {
	if (selected.value) {
		await mockApi.lifecycle(selected.value.simId, true)
		await load()
	}
}

async function doOffline() {
	if (selected.value) {
		await mockApi.lifecycle(selected.value.simId, false)
		await load()
	}
}

async function doAck(commandId: string, status = 'success') {
	if (!selected.value)
		return
	await mockApi.ack(selected.value.simId, commandId, status, '{}')
	ElMessage.success('已应答命令')
}

function onWs(ev: MockWsEvent) {
	const arr = feed[ev.simId] || (feed[ev.simId] = [])
	arr.push(ev)
	if (arr.length > 100)
		arr.splice(0, arr.length - 100)
}

function currentFeed(): MockWsEvent[] {
	if (!selected.value)
		return []
	return feed[selected.value.simId] || []
}

function fmtTs(ts?: number) {
	return ts ? new Date(ts).toLocaleTimeString() : ''
}

function feedLabel(type: string): string {
	switch (type) {
		case 'command':
			return '命令'
		case 'ota-down':
			return 'OTA 下发'
		case 'ota-progress':
			return 'OTA 进度'
		case 'ota-result':
			return 'OTA 结果'
		case 'ota-inform':
			return 'OTA 上报'
		case 'ack':
			return '应答'
		case 'down':
			return '下行'
		default:
			return '日志'
	}
}

function feedTagType(type: string): 'success' | 'info' | 'warning' | 'danger' | 'primary' {
	switch (type) {
		case 'command':
			return 'warning'
		case 'ota-down':
			return 'danger'
		case 'ota-progress':
			return 'primary'
		case 'ota-result':
		case 'ota-inform':
		case 'ack':
			return 'success'
		default:
			return 'info'
	}
}

function pretty(payload: unknown) {
	try {
		return JSON.stringify(payload, null, 2)
	}
	catch {
		return String(payload)
	}
}

function commandIdOf(payload: unknown): string {
	try {
		return (payload as any).commandId || ''
	}
	catch {
		return ''
	}
}

const DEVICE_TYPES = ['EDGE_GW', 'PCS', 'BMS', 'EMS', 'BATTERY_CLUSTER', 'ENERGY_CABINET', 'METER']

onMounted(() => {
	load()
	ws = connectMockWs(onWs)
	timer = window.setInterval(load, 5000)
})

onUnmounted(() => {
	if (ws)
		ws.close()
	if (timer)
		clearInterval(timer)
})
</script>

<template>
	<div class="sim">
		<div class="sim-head">
			<h1 class="ex-title">模拟设备</h1>
			<p class="ex-sub">
				可视化设备仿真器：一键建设备、上报属性/事件、接收命令并自动应答、仿真 OTA 全流程（直连 broker:18831）
			</p>
			<el-button type="primary" @click="openCreate">新建模拟设备</el-button>
		</div>

		<el-row :gutter="16">
			<!-- 左：设备列表 -->
			<el-col :span="9">
				<el-card shadow="never" class="sim-card">
					<template #header>设备列表（{{ devices.length }}）</template>
					<el-table :data="devices" v-loading="loading" highlight-current-row @current-change="selectDevice"
						empty-text="暂无模拟设备，点击右上角新建">
						<el-table-column prop="deviceName" label="设备" min-width="120">
							<template #default="{ row }">
								<div class="dev-name">{{ row.deviceName }}</div>
								<div class="dev-pk">{{ row.productKey }}</div>
							</template>
						</el-table-column>
						<el-table-column label="状态" width="92">
							<template #default="{ row }">
								<el-tag size="small" :type="row.connected ? 'success' : 'info'">
									{{ row.connected ? (row.online ? '在线' : '已连') : '离线' }}
								</el-tag>
								<el-tag size="small" :type="row.autoProvisioned ? 'primary' : 'warning'" class="ml">
									{{ row.autoProvisioned ? '建档' : '接管' }}
								</el-tag>
							</template>
						</el-table-column>
						<el-table-column label="操作" width="120">
							<template #default="{ row }">
								<el-button link type="primary" size="small" @click.stop="doStart(row)"
									:disabled="row.connected">启动</el-button>
								<el-button link type="warning" size="small" @click.stop="doStop(row)"
									:disabled="!row.connected">停止</el-button>
								<el-button link type="danger" size="small" @click.stop="doRemove(row)">删除</el-button>
							</template>
						</el-table-column>
					</el-table>
				</el-card>
			</el-col>

			<!-- 右：设备详情 -->
			<el-col :span="15">
				<el-empty v-if="!selected" description="请选择左侧设备" />
				<template v-else>
					<el-card shadow="never" class="sim-card">
						<template #header>
							<span>{{ selected.deviceName }}</span>
							<el-tag size="small" class="ml">{{ selected.productKey }}</el-tag>
							<el-tag size="small" class="ml" :type="selected.connected ? 'success' : 'info'">
								{{ selected.connected ? '已连接' : '未连接' }}
							</el-tag>
						</template>

						<el-tabs>
							<el-tab-pane label="上报 / 状态">
								<el-form label-width="80px" class="rk">
									<el-form-item label="上报类型">
										<el-radio-group v-model="reportType">
											<el-radio value="property">属性</el-radio>
											<el-radio value="event">事件</el-radio>
										</el-radio-group>
									</el-form-item>
									<el-form-item label="报文 JSON">
										<el-input type="textarea" :rows="5" v-model="reportJson"
											placeholder='{"soc":88.5,"power":5000}' />
									</el-form-item>
									<el-form-item>
										<el-button type="primary" @click="doReport">上报</el-button>
										<el-button type="success" plain @click="doOnline">上线</el-button>
										<el-button type="warning" plain @click="doOffline">下线</el-button>
									</el-form-item>
									<div class="tip">
										属性/事件按物模型字段上报（可在产品-物模型查看字段）；上报后于「影子」页查看 reported 态。
									</div>
								</el-form>
							</el-tab-pane>

							<el-tab-pane label="实时下行">
								<div v-if="currentFeed().length === 0" class="tip">暂无下行；在「指令中心」下发命令或「升级任务」触发后，此处实时显示。</div>
								<el-timeline class="feed">
									<el-timeline-item v-for="(ev, i) in currentFeed()" :key="i" :timestamp="fmtTs(ev.ts)"
										placement="top">
										<div class="feed-row">
											<el-tag size="small" :type="feedTagType(ev.type)">{{ feedLabel(ev.type) }}</el-tag>
											<span class="feed-topic">{{ ev.topic }}</span>
										</div>
										<pre class="feed-json">{{ pretty(ev.payload) }}</pre>
										<el-button v-if="ev.type === 'command'" size="small" type="primary"
											@click="doAck(commandIdOf(ev.payload))">
											应答成功
										</el-button>
									</el-timeline-item>
								</el-timeline>
							</el-tab-pane>

							<el-tab-pane label="设备日志">
								<el-table :data="selected.recentLogs" size="small" max-height="320" empty-text="暂无日志">
									<el-table-column prop="ts" label="时间" width="90" />
									<el-table-column prop="dir" label="方向" width="60">
										<template #default="{ row }">
											<el-tag size="small" :type="row.dir === 'in' ? 'warning' : (row.dir === 'out' ? 'success' : 'info')">
												{{ row.dir }}
											</el-tag>
										</template>
									</el-table-column>
									<el-table-column prop="note" label="说明" min-width="160" />
									<el-table-column label="报文" min-width="160">
										<template #default="{ row }">
											<el-popover v-if="row.payload" trigger="click" width="360">
												<pre class="feed-json">{{ row.payload }}</pre>
												<template #reference>
													<el-link type="primary" :underline="false">查看</el-link>
												</template>
											</el-popover>
										</template>
									</el-table-column>
								</el-table>
							</el-tab-pane>
						</el-tabs>
					</el-card>
				</template>
			</el-col>
		</el-row>

		<!-- 新建对话框 -->
		<el-dialog v-model="createVisible" title="新建模拟设备" width="520px">
			<el-form label-width="92px">
				<el-form-item label="模式">
					<el-radio-group v-model="createForm.mode">
						<el-radio value="auto">自动建档（平台创建设备并取密钥）</el-radio>
						<el-radio value="takeover">接管已有设备（填 secret）</el-radio>
					</el-radio-group>
				</el-form-item>
			<el-form-item label="产品" required>
				<el-select v-model="createForm.productKey" filterable placeholder="选择或输入产品标识" :loading="productLoading"
					style="width: 100%">
					<el-option v-for="p in products" :key="p.productKey" :label="`${p.productName} (${p.productKey})`"
						:value="p.productKey" />
				</el-select>
			</el-form-item>
			<el-form-item label="设备">
				<el-select v-model="createForm.deviceId" filterable placeholder="可选：选择产品下已有设备" :loading="deviceLoading"
					:disabled="!createForm.productKey" style="width: 100%" @change="pickDevice">
					<el-option v-for="d in deviceOptions" :key="d.deviceId" :label="d.deviceName" :value="d.deviceId" />
				</el-select>
				<div class="tip" style="margin-top: 4px">
					选择已有设备将自动带入设备名；接管模式仍需填写其 secret（见设备详情-重生成密钥复制）。
				</div>
			</el-form-item>
				<el-form-item label="设备名" required>
					<el-input v-model="createForm.deviceName" placeholder="如 mock-pcs-01（自动替换 _ 为 -）" />
				</el-form-item>
				<template v-if="createForm.mode === 'auto'">
					<el-form-item label="设备类型">
						<el-select v-model="createForm.deviceType" style="width: 100%">
							<el-option v-for="t in DEVICE_TYPES" :key="t" :label="t" :value="t" />
						</el-select>
					</el-form-item>
					<el-form-item label="初始版本">
						<el-input v-model="createForm.firmwareVersion" placeholder="可选，如 v1.0.0（OTA 仿真基线）" />
					</el-form-item>
				</template>
				<el-form-item v-else label="secret" required>
					<el-input v-model="createForm.secret" placeholder="设备密钥（设备页-重生成密钥复制）" />
				</el-form-item>
			</el-form>
			<template #footer>
				<el-button @click="createVisible = false">取消</el-button>
				<el-button type="primary" @click="submitCreate">创建并上线</el-button>
			</template>
		</el-dialog>
	</div>
</template>

<style scoped>
.sim-head {
	margin-bottom: 12px;
}

.ex-title {
	font-size: 20px;
	margin: 0 0 4px;
}

.ex-sub {
	color: var(--el-text-color-secondary);
	font-size: 13px;
	margin: 0 0 12px;
}

.sim-card {
	margin-bottom: 12px;
}

.dev-name {
	font-weight: 600;
}

.dev-pk {
	font-size: 12px;
	color: var(--el-text-color-secondary);
}

.ml {
	margin-left: 6px;
}

.rk {
	margin-top: 8px;
}

.tip {
	font-size: 12px;
	color: var(--el-text-color-secondary);
	line-height: 1.7;
}

.feed {
	margin-top: 8px;
	padding-left: 4px;
}

.feed-row {
	display: flex;
	align-items: center;
	gap: 8px;
}

.feed-topic {
	font-size: 12px;
	color: var(--el-text-color-secondary);
}

.feed-json {
	background: var(--el-fill-color-light);
	border-radius: 6px;
	padding: 8px;
	font-size: 12px;
	white-space: pre-wrap;
	word-break: break-all;
	margin: 6px 0;
	max-height: 220px;
	overflow: auto;
}
</style>
