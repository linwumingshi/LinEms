/** 权限判定：perms 含超管 *:*:* 恒真；required 空恒真；多 required 任一命中即真 */
export function hasPermi(perms: string[] | undefined, required: string | string[]): boolean {
  if (required == null || required === '' || (Array.isArray(required) && required.length === 0)) return true
  if (!perms) return false
  if (perms.includes('*:*:*')) return true
  const need = Array.isArray(required) ? required : [required]
  return need.some((p) => perms.includes(p))
}
