import type { WorldPlacement } from '../api/worldPlacementApi'

export type PlacementNumericField = 'x' | 'y' | 'z' | 'yaw' | 'pitch' | 'unlockGold'
export type PlacementNumericDrafts = Record<string, Partial<Record<PlacementNumericField, string>>>
export function parsePlacementNumber(raw: string, field: PlacementNumericField): number | null {
  if (!/^[+-]?(?:\d+(?:\.\d*)?|\.\d+)$/.test(raw.trim())) return null
  const value = Number(raw)
  return Number.isFinite(value) && (field !== 'unlockGold' || value >= 0) ? value : null
}
export function applyPlacementDrafts(placements: WorldPlacement[], drafts: PlacementNumericDrafts): WorldPlacement[] {
  return placements.map(item => {
    const fields = drafts[item.placementId]
    if (!fields) return item
    const next = { ...item }
    for (const field of Object.keys(fields) as PlacementNumericField[]) {
      const value = parsePlacementNumber(fields[field]!, field)
      if (value === null) continue
      if (field === 'unlockGold') { if (next.waystone) next.waystone = { ...next.waystone, unlockGold: value } }
      else next[field] = value
    }
    return next
  })
}
export function placementChanges(base: WorldPlacement[], working: WorldPlacement[]) {
  const saved = new Map(base.map(item => [item.placementId, JSON.stringify(item)]))
  let added = 0, changed = 0
  for (const item of working) {
    const original = saved.get(item.placementId)
    if (original === undefined) added++
    else { if (original !== JSON.stringify(item)) changed++; saved.delete(item.placementId) }
  }
  return { added, changed, deleted: saved.size }
}
