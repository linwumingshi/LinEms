import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import StrategyConfigEditor from '@/components/StrategyConfigEditor.vue'

/** stub 掉重控件（time-picker / input-number / select），happy-dom 下仅断言结构类名，避免 EP 内部渲染抖动 */
function mountEditor(modelValue: string, strategyType: string) {
  return mount(StrategyConfigEditor, {
    props: { modelValue, strategyType },
    global: {
      plugins: [ElementPlus],
      stubs: {
        ElTimePicker: true, ElInputNumber: true, ElSelect: true, ElOption: true,
        'el-time-picker': true, 'el-input-number': true, 'el-select': true, 'el-option': true,
      },
    },
  })
}

describe('StrategyConfigEditor', () => {
  it('ID1：非峰谷 + 空配置 → 无「不是合法 JSON」错误态', () => {
    const wrapper = mountEditor('', '')
    expect(wrapper.find('.json-state.err').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('配置不是合法 JSON')
  })

  it('ID1：峰谷 + 空配置 → 结构化空表、无阻断警报', () => {
    const wrapper = mountEditor('', 'PEAK_VALLEY')
    expect(wrapper.findAll('.window-row')).toHaveLength(0)
    expect(wrapper.find('.block-alert').exists()).toBe(false)
  })

  it('峰谷 + 非空配置 → 结构化表单回显窗口数', () => {
    const config = JSON.stringify({
      chargeWindows: [{ start: '02:00', end: '06:00', powerLimit: 100 }],
      dischargeWindows: [],
    })
    const wrapper = mountEditor(config, 'PEAK_VALLEY')
    expect(wrapper.findAll('.window-row')).toHaveLength(1)
  })

  it('峰谷 + priceDriven config → 渲染电价驱动开关与功率输入，无窗口表', () => {
    const config = JSON.stringify({ priceDriven: true, chargePower: 80 })
    const wrapper = mountEditor(config, 'PEAK_VALLEY')
    expect(wrapper.find('.price-drive-bar').exists()).toBe(true)
    expect(wrapper.findAll('.window-row')).toHaveLength(0)
  })

  it('峰谷 + 手工 config → 渲染窗口表，无功率输入', () => {
    const config = JSON.stringify({
      chargeWindows: [{ start: '02:00', end: '06:00', powerLimit: 100 }],
      dischargeWindows: [],
    })
    const wrapper = mountEditor(config, 'PEAK_VALLEY')
    expect(wrapper.findAll('.window-row')).toHaveLength(1)
    expect(wrapper.find('.power-fields').exists()).toBe(false)
  })

  it('需量 + 空配置 → 结构化模式、窗口表空、需量限值输入在、无阻断警报', () => {
    const wrapper = mountEditor('', 'DEMAND')
    expect(wrapper.findAll('.window-row')).toHaveLength(0)
    expect(wrapper.find('.demand-limit-row').exists()).toBe(true)
    expect(wrapper.find('.block-alert').exists()).toBe(false)
  })

  it('需量 + 非空配置 → 窗口表回显行数', () => {
    const config = JSON.stringify({
      chargeWindows: [{ start: '02:00', end: '06:00', powerLimit: 100 }],
      dischargeWindows: [{ start: '14:00', end: '16:00', powerLimit: 200 }],
      demandLimit: 500,
    })
    const wrapper = mountEditor(config, 'DEMAND')
    expect(wrapper.findAll('.window-row')).toHaveLength(2)
  })

  it('时间策略 + 空配置 → 结构化空时段表、无阻断警报', () => {
    const wrapper = mountEditor('', 'TIME')
    expect(wrapper.findAll('.schedule-row')).toHaveLength(0)
    expect(wrapper.find('.block-alert').exists()).toBe(false)
  })

  it('时间策略 + 非空配置 → 时段表回显行数（含 STANDBY 段）', () => {
    const config = JSON.stringify({
      schedule: [
        { start: '08:00', end: '09:00', action: 'CHARGE', power: 100 },
        { start: '14:00', end: '15:00', action: 'DISCHARGE', power: 80 },
        { start: '18:00', end: '19:00', action: 'STANDBY' },
      ],
    })
    const wrapper = mountEditor(config, 'TIME')
    expect(wrapper.findAll('.schedule-row')).toHaveLength(3)
  })

  it('空 JSON 点格式化 → 静默忽略，不弹「不是合法 JSON」', async () => {
    document.body.innerHTML = ''
    const wrapper = mountEditor('', '')
    await wrapper.find('.json-toolbar button').trigger('click')
    expect(document.body.textContent).not.toContain('配置不是合法 JSON')
  })

  it('跨类型切换：DEMAND 形状 config 切到 TIME → 重置为 TIME 结构化空表（不被强制 JSON）', async () => {
    const demandCfg = JSON.stringify({ chargeWindows: [{ start: '02:00', end: '06:00', powerLimit: 100 }] })
    const wrapper = mountEditor(demandCfg, 'DEMAND')
    await wrapper.setProps({ strategyType: 'TIME' })
    // modelValue 仍是 DEMAND 形状，TIME 无法解析 → 应重置为结构化空时段表，而非 JSON 模式
    expect(wrapper.find('.schedule-group').exists()).toBe(true)
    expect(wrapper.find('.json-toolbar').exists()).toBe(false)
    expect(wrapper.find('.mode-alert').exists()).toBe(false)
  })

  it('跨类型切换：TIME 形状 config 切到 DEMAND → 重置为 DEMAND 结构化空窗口表', async () => {
    const timeCfg = JSON.stringify({ schedule: [{ start: '08:00', end: '09:00', action: 'CHARGE', power: 100 }] })
    const wrapper = mountEditor(timeCfg, 'TIME')
    await wrapper.setProps({ strategyType: 'DEMAND' })
    expect(wrapper.find('.demand-limit-row').exists()).toBe(true)
    expect(wrapper.find('.json-toolbar').exists()).toBe(false)
  })
})
