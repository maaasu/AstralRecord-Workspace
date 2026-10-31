import type { EquipmentRow } from './masterAnalytics'

/** Compare the values shown in the table, across all pages and before formatting. */
export function differingEquipmentStatuses(rows: readonly Pick<EquipmentRow, 'stats'>[], statuses: readonly string[]): string[] {
  if (rows.length < 2) return [...statuses]
  return statuses.filter(id => {
    const first = rows[0].stats[id]
    return rows.some(row => {
      const value = row.stats[id]
      return value?.min !== first?.min || value?.max !== first?.max || value?.expected !== first?.expected
    })
  })
}
