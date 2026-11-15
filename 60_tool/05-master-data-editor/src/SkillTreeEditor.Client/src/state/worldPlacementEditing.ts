import type { WorldPlacement } from '../api/worldPlacementApi'

export type PlacementNumericField = 'x' | 'y' | 'z' | 'yaw' | 'pitch' | 'unlockGold'
export type PlacementNumericDrafts = Record<string, Partial<Record<PlacementNumericField, string>>>
/** Resolve against the current world and working copy; stale/unknown selections cannot mutate another world. */
export function placementSelectionScope(working: WorldPlacement[], selectedIds: ReadonlySet<string>, worldId: string, filteredIds: ReadonlySet<string>) {
  const items = working.filter(item => item.worldId === worldId && selectedIds.has(item.placementId))
  const ids = new Set(items.map(item => item.placementId))
  const matchingCount = items.reduce((count, item) => count + Number(filteredIds.has(item.placementId)), 0)
  return { items, ids, matchingCount, outsideCount: items.length - matchingCount }
}
export function setPlacementsEnabled(working: WorldPlacement[], ids: ReadonlySet<string>, worldId: string, enabled: boolean): WorldPlacement[] {
  let changed = false
  const next = working.map(item => {
    if (item.worldId !== worldId || !ids.has(item.placementId) || item.enabled === enabled) return item
    changed = true
    return { ...item, enabled }
  })
  return changed ? next : working
}
export function removePlacements(working: WorldPlacement[], ids: ReadonlySet<string>, worldId: string): WorldPlacement[] {
  const next = working.filter(item => item.worldId !== worldId || !ids.has(item.placementId))
  return next.length === working.length ? working : next
}
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
const placementFields: Record<Exclude<keyof WorldPlacement, 'waystone'>, true> = {
  placementId: true, kind: true, worldId: true, worldName: true, templateId: true,
  x: true, y: true, z: true, yaw: true, pitch: true, enabled: true,
}
const waystoneFields: Record<keyof NonNullable<WorldPlacement['waystone']>, true> = {
  id: true, name: true, lockEnabled: true, unlockGold: true, icon: true, createdAt: true, createdBy: true,
}
const placementKeys = Object.keys(placementFields) as (keyof typeof placementFields)[]
const waystoneKeys = Object.keys(waystoneFields) as (keyof typeof waystoneFields)[]
export function samePlacement(a: WorldPlacement, b: WorldPlacement): boolean {
  if (a === b) return true
  return placementKeys.every(key => a[key] === b[key]) && (a.waystone === b.waystone
    || !!a.waystone && !!b.waystone && waystoneKeys.every(key => a.waystone![key] === b.waystone![key]))
}
export type PlacementChangeKind = 'added' | 'changed' | 'deleted'
export interface PlacementChange { id: string; kind: PlacementChangeKind; before?: WorldPlacement; after?: WorldPlacement }
export function placementChangeEntries(base: WorldPlacement[], working: WorldPlacement[]): PlacementChange[] {
  const saved = new Map(base.map(item => [item.placementId, item]))
  const changes: PlacementChange[] = []
  for (const after of working) {
    const before = saved.get(after.placementId)
    if (!before) changes.push({ id: after.placementId, kind: 'added', after })
    else if (!samePlacement(before, after)) changes.push({ id: after.placementId, kind: 'changed', before, after })
    saved.delete(after.placementId)
  }
  for (const before of saved.values()) changes.push({ id: before.placementId, kind: 'deleted', before })
  return changes
}
export function revertPlacementChange(working: WorldPlacement[], change: PlacementChange): WorldPlacement[] {
  if (!change.before) return working.filter(item => item.placementId !== change.id)
  if (!change.after) return [...working, change.before]
  return working.map(item => item.placementId === change.id ? change.before! : item)
}
