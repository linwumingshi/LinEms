/** 场景联动规则 DSL 选项常量（与后端 DslValidator 对齐） */

/** 比较操作符 */
export const opOptions: Array<{ label: string; value: string }> = [
  { label: '>', value: 'GT' },
  { label: '≥', value: 'GTE' },
  { label: '<', value: 'LT' },
  { label: '≤', value: 'LTE' },
  { label: '=', value: 'EQ' },
  { label: '≠', value: 'NEQ' },
]

/** 触发器类型 */
export const triggerTypeOptions: Array<{ label: string; value: string }> = [
  { label: '属性触发', value: 'PROPERTY' },
  { label: '定时触发', value: 'TIMER' },
  { label: '上下线触发', value: 'LIFECYCLE' },
  { label: '告警触发', value: 'ALARM' },
  { label: '手动触发', value: 'MANUAL' },
]

/** 执行条件类型 */
export const conditionTypeOptions: Array<{ label: string; value: string }> = [
  { label: '设备状态', value: 'DEVICE_STATUS' },
  { label: '时间范围', value: 'TIME_RANGE' },
  { label: '属性条件', value: 'PROPERTY' },
]

/** 动作类型 */
export const actionTypeOptions: Array<{ label: string; value: string }> = [
  { label: '设备命令', value: 'DEVICE_COMMAND' },
  { label: '触发告警', value: 'ALARM' },
  { label: 'Webhook 通知', value: 'NOTIFY' },
  { label: '嵌套规则', value: 'RULE' },
]

/** 告警级别文本 */
export const severityText = (level: number | null | undefined): string => {
  switch (level) {
    case 1:
      return '提示'
    case 2:
      return '一般'
    case 3:
      return '严重'
    case 4:
      return '危急'
    default:
      return '-'
  }
}

/** 触发类型中文（执行日志用） */
export const triggerTypeText = (t: string | null | undefined): string => {
  switch (t) {
    case 'PROPERTY':
      return '属性'
    case 'TIMER':
      return '定时'
    case 'LIFECYCLE':
      return '上下线'
    case 'ALARM':
      return '告警'
    case 'MANUAL':
      return '手动'
    case 'RULE':
      return '嵌套'
    default:
      return t ?? '-'
  }
}
