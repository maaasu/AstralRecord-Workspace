import { memo, useState } from 'react'
import type { PlacementCatalog } from './WorldPlacementEditor'
import { spawnerLevels, spawnerMasterColor } from '../data/spawnerMapStyle'
import { stripMinecraftFormatting as plainName } from '../utils/minecraft'

interface Props {
  ids: readonly (string | null)[]
  masters: ReadonlyMap<string, PlacementCatalog['spawners'][number]>
  numbers: ReadonlyMap<string, number>
  mode: 'masters' | 'levels'
}

// Pointer/view changes in the map do not change the legend's inputs. Keep its
// rows out of those renders, and don't build hidden rows while details is closed.
export const SpawnerMasterLegend = memo(function SpawnerMasterLegend({ ids, masters, numbers, mode }: Props) {
  const [expanded, setExpanded] = useState(false)
  return <details onToggle={event => setExpanded(event.currentTarget.open)}>
    <summary>表示中のスポナーマスター ({ids.length}) · 番号は一覧と共通</summary>
    {expanded && <div className="sp-master-key-list">{ids.map(id => {
      const master = masters.get(id ?? ''), levels = spawnerLevels(master?.levels ?? [])
      return <span key={id ?? 'unknown'} title={id ?? 'マスター未選択'}><b style={{ borderColor: mode === 'masters' ? spawnerMasterColor(id) : levels.color }}>#{numbers.get(id ?? '') ?? '?'}</b><span>{plainName(master?.name ?? id ?? 'マスター未選択')}<small>{levels.label} · {id ?? 'マスター未選択'}</small></span></span>
    })}</div>}
  </details>
})
