import type { SysEnterprise } from '@/types/models'

/** 在单位树中按 enterpriseId 深搜（先序）。id 为空或未命中返回 null。 */
export function findEnterpriseNode(
  tree: SysEnterprise[],
  id: string | null | undefined,
): SysEnterprise | null {
  if (!id) return null
  const key = String(id)
  for (const node of tree) {
    if (String(node.enterpriseId) === key) return node
    if (node.children?.length) {
      const hit = findEnterpriseNode(node.children, id)
      if (hit) return hit
    }
  }
  return null
}

/** 先序平铺整棵树（上级下拉 / 遍历用）。 */
export function flatEnterpriseTree(tree: SysEnterprise[]): SysEnterprise[] {
  const out: SysEnterprise[] = []
  const walk = (nodes: SysEnterprise[]): void => {
    for (const n of nodes) {
      out.push(n)
      if (n.children?.length) walk(n.children)
    }
  }
  walk(tree)
  return out
}

/** 收集节点及其全部后代的 enterpriseId 集合（编辑上级时排除自身子树）。 */
export function collectSubtreeIds(node: SysEnterprise): Set<string> {
  const ids = new Set<string>([String(node.enterpriseId)])
  const walk = (n: SysEnterprise): void => {
    for (const c of n.children ?? []) {
      ids.add(String(c.enterpriseId))
      walk(c)
    }
  }
  walk(node)
  return ids
}
