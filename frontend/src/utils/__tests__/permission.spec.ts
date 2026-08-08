// frontend/src/utils/__tests__/permission.spec.ts
import { describe, expect, it } from 'vitest'
import { hasPermi } from '@/utils/permission'

describe('hasPermi', () => {
  it('无 required 恒真', () => {
    expect(hasPermi(['x'], '')).toBe(true)
    expect(hasPermi(undefined, [])).toBe(true)
  })

  it('无 perms 恒假', () => {
    expect(hasPermi(undefined, 'system:user:list')).toBe(false)
    expect(hasPermi([], 'system:user:list')).toBe(false)
  })

  it('超管 *:*:* 恒真', () => {
    expect(hasPermi(['*:*:*'], 'system:user:list')).toBe(true)
    expect(hasPermi(['system:role:list', '*:*:*'], 'system:perm:list')).toBe(true)
  })

  it('单权限精确匹配', () => {
    expect(hasPermi(['system:user:list'], 'system:user:list')).toBe(true)
    expect(hasPermi(['system:user:list'], 'system:user:add')).toBe(false)
  })

  it('多权限任一命中即真', () => {
    expect(hasPermi(['system:role:list'], ['system:user:list', 'system:role:list'])).toBe(true)
    expect(hasPermi(['system:role:list'], ['system:user:list', 'system:user:add'])).toBe(false)
  })
})
