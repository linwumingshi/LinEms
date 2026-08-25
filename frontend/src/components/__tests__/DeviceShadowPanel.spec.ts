import { describe, expect, it, vi, beforeEach } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { nextTick } from 'vue'
import ElementPlus from 'element-plus'
import DeviceShadowPanel from '@/components/DeviceShadowPanel.vue'
import { shadowApi } from '@/api/shadow'
import { productApi } from '@/api/product'
import { ElMessage } from 'element-plus'

/** mock 影子 API：不触后端，断言面板自足行为 */
vi.mock('@/api/shadow', () => ({
  shadowApi: {
    getShadow: vi.fn(),
    setDesired: vi.fn(),
  },
}))

/** mock 产品 API：物模型驱动编辑器数据源 */
vi.mock('@/api/product', () => ({
  productApi: {
    thingModelByKey: vi.fn(),
  },
}))

/** mock ElMessage：避免 happy-dom 下真实弹出 DOM，并断言提示文案 */
vi.mock('element-plus', async (importOriginal) => {
  const actual = await importOriginal<typeof import('element-plus')>()
  return {
    ...actual,
    ElMessage: { success: vi.fn(), error: vi.fn(), warning: vi.fn() },
  }
})

const mockGet = shadowApi.getShadow as ReturnType<typeof vi.fn>
const mockSet = shadowApi.setDesired as ReturnType<typeof vi.fn>
const mockModel = productApi.thingModelByKey as ReturnType<typeof vi.fn>

/** 物模型 fixture：含数字/枚举/布尔可写属性与只读属性 soc（不应出现在下拉） */
const SCHEMA = {
  properties: [
    { identifier: 'power', name: '功率', dataType: 'int', unit: 'W', accessMode: 'rw', specs: { min: 0, max: 5000, step: 100 } },
    { identifier: 'mode', name: '模式', dataType: 'enum', accessMode: 'w', specs: { enumValues: [{ value: 0, label: '关' }, { value: 1, label: '开' }] } },
    { identifier: 'alarm', name: '告警', dataType: 'bool', accessMode: 'rw' },
    { identifier: 'soc', name: 'SOC', dataType: 'float', accessMode: 'r' },
  ],
  services: [],
  events: [],
}

function mockModelResolved(): void {
  mockModel.mockResolvedValue({
    modelId: '1', productId: '1', version: 'v1', status: 1, isCurrent: 1,
    schemaJson: JSON.stringify(SCHEMA),
  })
}

function mountPanel(deviceId = '1', productKey?: string) {
  return mount(DeviceShadowPanel, {
    props: { deviceId, ...(productKey ? { productKey } : {}) },
    global: { plugins: [ElementPlus] },
  })
}

/** 点击「下发 desired」按钮 */
async function clickSubmit(wrapper: ReturnType<typeof mountPanel>): Promise<void> {
  const submit = wrapper.findAll('button').find((b) => b.text().includes('下发 desired'))
  await submit!.trigger('click')
  await flushPromises()
}

describe('DeviceShadowPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockGet.mockResolvedValue({ deviceId: '1', reported: { soc: 50 }, desired: {}, version: 1 })
    mockSet.mockResolvedValue({ deviceId: '1', desired: {}, delta: { power: 5000 }, version: 2 })
  })

  it('挂载即按 deviceId 加载影子：展示版本与 reported/desired 键', async () => {
    const wrapper = mountPanel('1')
    await flushPromises()
    expect(mockGet).toHaveBeenCalledWith('1')
    expect(wrapper.text()).toContain('乐观锁版本')
    expect(wrapper.text()).toContain('soc')        // reported 属性
    expect(wrapper.text()).toContain('设置 desired')
  })

  it('deviceId 变更自动重新加载（详情抽屉换设备场景）', async () => {
    const wrapper = mountPanel('1')
    await flushPromises()
    await wrapper.setProps({ deviceId: '2' })
    await flushPromises()
    expect(mockGet).toHaveBeenCalledTimes(2)
    expect(mockGet).toHaveBeenLastCalledWith('2')
  })

  it('加载失败：展示空态并弹出错误提示', async () => {
    mockGet.mockRejectedValue(new Error('影子不存在'))
    const wrapper = mountPanel('1')
    await flushPromises()
    await flushPromises()
    expect(ElMessage.error).toHaveBeenCalledWith('影子不存在')
    expect(wrapper.text()).toContain('暂无影子数据')
  })

  it('desired 下发（自由输入兜底）：填写属性行后提交，成功展示 delta 提示', async () => {
    const wrapper = mountPanel('1')
    await flushPromises()
    const inputs = wrapper.findAll('input')
    await inputs[0].setValue('power')
    await inputs[1].setValue('5000')
    await clickSubmit(wrapper)
    expect(mockSet).toHaveBeenCalledWith('1', { power: 5000 })
    expect(ElMessage.success).toHaveBeenCalledWith('desired 下发成功，delta 属性数：1')
    expect(wrapper.text()).toContain('power')
  })

  it('desired 全空行：拒绝下发并提示', async () => {
    const wrapper = mountPanel('1')
    await flushPromises()
    await clickSubmit(wrapper)
    expect(mockSet).not.toHaveBeenCalled()
    expect(ElMessage.warning).toHaveBeenCalledWith('请至少填写一个期望属性')
  })

  it('传入 productKey：编辑器升级为物模型驱动（属性下拉 + 仅可写属性提示）', async () => {
    mockModelResolved()
    const wrapper = mountPanel('1', 'meter')
    await flushPromises()
    expect(mockModel).toHaveBeenCalledWith('meter')
    expect(wrapper.text()).toContain('已按产品物模型渲染 · 仅可写属性（3 个）')
    expect(wrapper.find('.row-key').exists()).toBe(true)   // 属性下拉占位
  })

  it('物模型驱动：选择数字属性渲染数值控件，提交下发正确类型', async () => {
    mockModelResolved()
    const wrapper = mountPanel('1', 'meter')
    await flushPromises()
    // 模拟属性下拉选择 power（rw/int）
    const propSelect = wrapper.findAllComponents({ name: 'ElSelect' })[0]
    await propSelect.vm.$emit('update:modelValue', 'power')
    await propSelect.vm.$emit('change', 'power')
    await nextTick()
    const numInput = wrapper.findComponent({ name: 'ElInputNumber' })
    expect(numInput.exists()).toBe(true)                 // 数字属性 → el-input-number
    await numInput.vm.$emit('update:modelValue', 5000)
    await clickSubmit(wrapper)
    expect(mockSet).toHaveBeenCalledWith('1', { power: 5000 })
  })

  it('物模型驱动：枚举属性渲染枚举下拉，提交下发枚举原始值', async () => {
    mockModelResolved()
    const wrapper = mountPanel('1', 'meter')
    await flushPromises()
    const selects = wrapper.findAllComponents({ name: 'ElSelect' })
    await selects[0].vm.$emit('update:modelValue', 'mode')
    await selects[0].vm.$emit('change', 'mode')
    await nextTick()
    // 第二个下拉为枚举值选择（默认选中首项 0，改选 1）
    const valueSelect = wrapper.findAllComponents({ name: 'ElSelect' })[1]
    await valueSelect.vm.$emit('update:modelValue', 1)
    await clickSubmit(wrapper)
    expect(mockSet).toHaveBeenCalledWith('1', { mode: 1 })
  })

  it('物模型加载失败：desired 编辑器降级为自由输入（历史行为兜底）', async () => {
    mockModel.mockRejectedValue(new Error('物模型未发布'))
    const wrapper = mountPanel('1', 'meter')
    await flushPromises()
    expect(wrapper.text()).not.toContain('已按产品物模型渲染')
    expect(wrapper.text()).toContain('值可填 JSON 或字符串')
    expect(wrapper.findAll('input').length).toBeGreaterThanOrEqual(2)
  })
})
