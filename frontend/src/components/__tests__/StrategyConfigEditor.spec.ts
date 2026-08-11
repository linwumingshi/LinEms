import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import StrategyConfigEditor from '@/components/StrategyConfigEditor.vue'

/** stub 掉重控件（time-picker / input-number），happy-dom 下仅断言结构类名，避免 EP 内部渲染抖动 */
function mountEditor(modelValue: string, strategyType: string) {
  return mount(StrategyConfigEditor, {
    props: { modelValue, strategyType },
    global: {
      plugins: [ElementPlus],
      stubs: {
        ElTimePicker: true, ElInputNumber: true,
        'el-time-picker': true, 'el-input-number': true,
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
})
