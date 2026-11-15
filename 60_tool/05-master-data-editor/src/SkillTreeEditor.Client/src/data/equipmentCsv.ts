import type { EquipmentRow } from './masterAnalytics'

/** Append export context after the existing columns so their positions stay intact. */
export function equipmentCsvRows(rows: readonly EquipmentRow[], statuses: readonly string[], requestedEnhanceLevel: number): unknown[][] {
  return [
    ['ID', '名前', 'スロット', 'タグ', '要求Lv', '進行度', ...statuses.flatMap(id => [`${id} 下限`, `${id} 上限`]),
      ...statuses.map(id => `${id} 期待値`), '指定強化Lv', 'ソースパス'],
    ...rows.map(row => [row.id, row.name, row.slot, row.tag, row.level, row.progression,
      ...statuses.flatMap(id => [row.stats[id]?.min, row.stats[id]?.max]),
      ...statuses.map(id => row.stats[id]?.expected), requestedEnhanceLevel, row.path]),
  ]
}
