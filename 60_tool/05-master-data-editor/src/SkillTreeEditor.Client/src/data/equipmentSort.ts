import type { EquipmentRow } from './masterAnalytics'

export type EquipmentSortKey = 'default' | 'name' | 'id' | 'level' | 'progression' | 'min' | 'max'
export type EquipmentSortDirection = 'asc' | 'desc'
const names = new Intl.Collator('ja-JP', { numeric: true })

/** Input order breaks ties: level/ID order for candidates, selection order for selected rows. */
export function sortEquipment(rows: EquipmentRow[], key: EquipmentSortKey, direction: EquipmentSortDirection, status: string) {
  if (key === 'default') return rows
  const value = (row: EquipmentRow) => {
    const raw = key === 'min' || key === 'max' ? row.stats[status]?.[key] : row[key]
    return typeof raw === 'number' && !Number.isFinite(raw) ? undefined : raw
  }
  return [...rows].sort((a, b) => {
    const left = value(a), right = value(b)
    // Missing values stay last in either direction; zero and negative values remain sortable.
    if (left == null || right == null) return left == null ? right == null ? 0 : 1 : -1
    const difference = typeof left === 'string' && typeof right === 'string' ? names.compare(left, right) : Number(left) - Number(right)
    return direction === 'asc' ? difference : -difference
  })
}
