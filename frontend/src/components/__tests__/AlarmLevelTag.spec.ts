import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import AlarmLevelTag from '@/components/AlarmLevelTag.vue'

describe('AlarmLevelTag', () => {
  it('level=3 渲染「严重」且为 warning 样式', () => {
    const wrapper = mount(AlarmLevelTag, {
      props: { level: 3 },
      global: { plugins: [ElementPlus] },
    })
    expect(wrapper.text()).toContain('严重')
    expect(wrapper.find('.el-tag--warning').exists()).toBe(true)
  })

  it('level=4 渲染「危急」且为 danger 样式', () => {
    const wrapper = mount(AlarmLevelTag, {
      props: { level: 4 },
      global: { plugins: [ElementPlus] },
    })
    expect(wrapper.text()).toContain('危急')
    expect(wrapper.find('.el-tag--danger').exists()).toBe(true)
  })

  it('hideText=true 时仅渲染色块', () => {
    const wrapper = mount(AlarmLevelTag, {
      props: { level: 1, hideText: true },
      global: { plugins: [ElementPlus] },
    })
    expect(wrapper.text()).not.toContain('提示')
  })
})
