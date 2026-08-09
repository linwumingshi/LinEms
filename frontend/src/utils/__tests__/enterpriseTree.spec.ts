import { describe, expect, it } from 'vitest'
import { collectSubtreeIds, findEnterpriseNode, flatEnterpriseTree } from '@/utils/enterpriseTree'
import type { SysEnterprise } from '@/types/models'

function node(id: string, name: string, children?: SysEnterprise[]): SysEnterprise {
  return {
    enterpriseId: id, tenantId: '1', parentId: '0', path: `/${id}/`, level: 1,
    enterpriseCode: name, enterpriseName: name, sort: 0, status: 1,
    createTime: '', updateTime: '', deleted: 0, children,
  }
}

const tree: SysEnterprise[] = [
  node('1', '集团总部', [
    node('2', '华东区域', [node('3', '上海公司'), node('4', '浙江公司')]),
    node('5', '华南区域', [node('6', '广东公司')]),
  ]),
]

describe('findEnterpriseNode', () => {
  it('命中根节点', () => {
    expect(findEnterpriseNode(tree, '1')?.enterpriseName).toBe('集团总部')
  })
  it('命中深层子节点', () => {
    expect(findEnterpriseNode(tree, '6')?.enterpriseName).toBe('广东公司')
  })
  it('未命中返回 null', () => {
    expect(findEnterpriseNode(tree, '99')).toBeNull()
  })
  it('空树返回 null', () => {
    expect(findEnterpriseNode([], '1')).toBeNull()
  })
  it('null/undefined id 返回 null', () => {
    expect(findEnterpriseNode(tree, null)).toBeNull()
    expect(findEnterpriseNode(tree, undefined)).toBeNull()
  })
})

describe('flatEnterpriseTree', () => {
  it('先序平铺全部节点', () => {
    expect(flatEnterpriseTree(tree).map((n) => n.enterpriseId)).toEqual(['1', '2', '3', '4', '5', '6'])
  })
  it('空树返回空数组', () => {
    expect(flatEnterpriseTree([])).toEqual([])
  })
})

describe('collectSubtreeIds', () => {
  it('收集自身与全部后代', () => {
    const root = findEnterpriseNode(tree, '1')!
    expect(Array.from(collectSubtreeIds(root))).toEqual(['1', '2', '3', '4', '5', '6'])
  })
  it('叶子节点仅自身', () => {
    const leaf = findEnterpriseNode(tree, '3')!
    expect(Array.from(collectSubtreeIds(leaf))).toEqual(['3'])
  })
})
