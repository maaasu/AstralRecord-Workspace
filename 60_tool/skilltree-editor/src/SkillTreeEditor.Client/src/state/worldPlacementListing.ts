import type { WorldPlacement } from '../api/worldPlacementApi'
import { stripMinecraftFormatting } from '../utils/minecraft'

export type PlacementListingSort = 'default' | 'distance' | 'level-asc' | 'level-desc' | 'name'
export interface PlacementListingInfo { name: string; levels: readonly number[] }

export function sortWorldPlacementListing(
  placements: readonly WorldPlacement[],
  sort: PlacementListingSort,
  display: ReadonlyMap<string, PlacementListingInfo>,
  spawn: { x: number; z: number },
): { items: WorldPlacement[]; distances: ReadonlyMap<string, number> } {
  if (sort === 'default') return { items: [...placements], distances: new Map() }
  const distances = new Map<string, number>()
  const entries = placements.map((item, index) => {
    const info = display.get(item.placementId)
    const levels = (sort === 'level-asc' || sort === 'level-desc') && item.kind === 'spawner'
      ? info?.levels.filter(Number.isFinite) ?? [] : []
    const distance = sort === 'distance' ? Math.hypot(item.x - spawn.x, item.z - spawn.z) : null
    if (distance !== null && Number.isFinite(distance)) distances.set(item.placementId, distance)
    return {
      item,
      index,
      name: sort === 'name' ? stripMinecraftFormatting(info?.name ?? '').trim() : '',
      level: levels.length ? (sort === 'level-desc' ? Math.max(...levels) : Math.min(...levels)) : null,
      distance: distance !== null && Number.isFinite(distance) ? distance : null,
    }
  })
  const compareOptionalNumber = (left: number | null, right: number | null, descending = false) =>
    left === null ? (right === null ? 0 : 1) : right === null ? -1 : descending ? right - left : left - right
  const collator = sort === 'name' ? new Intl.Collator('ja-JP', { numeric: true, sensitivity: 'base' }) : null
  entries.sort((left, right) => {
    const order = sort === 'distance' ? compareOptionalNumber(left.distance, right.distance)
      : sort === 'level-asc' || sort === 'level-desc' ? compareOptionalNumber(left.level, right.level, sort === 'level-desc')
        : collator!.compare(left.name, right.name)
    return order || left.index - right.index
  })
  return { items: entries.map(entry => entry.item), distances }
}
