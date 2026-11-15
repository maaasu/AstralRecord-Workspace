import { useEffect, useMemo, useState } from 'react'
import type { PlanningCell } from '../data/spawnerPlanning'
import { coversCellCenter, type CoverageSource } from '../data/spawnerCoverage'

interface Props {
  cells: PlanningCell[]
  cellSize: number
  indices: number[][]
  sources: CoverageSource[]
  unknown: number
  selectedCell: number | null
  disabled: boolean
  onFocus: (index: number) => void
  onSource: (id: string) => void
}

export function SpawnerCoverageInspector({ cells, cellSize, indices, sources, unknown, selectedCell, disabled, onFocus, onSource }: Props) {
  const [category, setCategory] = useState(0)
  const [limit, setLimit] = useState(50)
  const eligible = useMemo(() => new Set(indices.flat()), [indices])
  const targets = indices[category]
  const position = useMemo(() => selectedCell === null ? -1 : targets.indexOf(selectedCell), [targets, selectedCell])
  const cell = selectedCell === null ? undefined : cells[selectedCell]
  const included = selectedCell !== null && eligible.has(selectedCell)
  // Only the selected cell is matched against sources. Pointer/view changes reuse it.
  const matches = useMemo(() => cell && included ? sources.filter(source => coversCellCenter(source, cell, cellSize)) : [], [cell, included, sources, cellSize])
  useEffect(() => { setLimit(50) }, [cell, sources])
  const navigate = (direction: number) => {
    if (disabled || !targets.length) return
    const next = position < 0 ? direction > 0 ? 0 : targets.length - 1 : (position + direction + targets.length) % targets.length
    onFocus(targets[next])
  }
  return <div className="sp-coverage-inspector">
    <div className="sp-coverage-navigation">
      <label>確認する区画<select value={category} onChange={event => setCategory(Number(event.target.value))}>
        <option value={0}>{unknown ? '既知範囲0件' : '未カバー'}（{indices[0].length.toLocaleString('ja-JP')}区画）</option>
        <option value={2}>重複（{indices[2].length.toLocaleString('ja-JP')}区画）</option>
      </select></label>
      <button className="button" disabled={disabled || !targets.length} onClick={() => navigate(-1)}>前の区画</button>
      <button className="button" disabled={disabled || !targets.length} onClick={() => navigate(1)}>次の区画</button>
      <span aria-live="polite">{position < 0 ? '未選択' : `${position + 1} / ${targets.length}`}</span>
    </div>
    <p>現在の表示条件内を解析順に巡回します。地図の空いている地点をクリックしても確認できます。</p>
    {cell && <section aria-label="選択区画の出現範囲" className="sp-coverage-cell">
      <strong>X {cell.x} / Z {cell.z}（中心 X {cell.x + cellSize / 2} / Z {cell.z + cellSize / 2}）</strong>
      {!included ? <p>選択区画: 判定対象外または表示条件外の区画です。0件とは判定しません。</p> : <>
        <p aria-live="polite">選択区画: 既知範囲{matches.length}件{matches.length >= 2 ? 'が重複' : ''}{unknown > 0 ? '（不明な範囲は含みません）' : ''}</p>
        {!matches.length && <p>{unknown ? '既知の出現範囲は届いていません。不明な範囲があるため未カバーとは断定できません。' : '表示対象の出現範囲は、この区画の中心に届いていません。'}</p>}
        <div className="sp-coverage-sources">{matches.slice(0, limit).map(source => <button className="button" key={source.id} disabled={disabled} onClick={() => onSource(source.id)}>
          <strong>{source.label}</strong><small>X {source.x - .5} / Z {source.z - .5} · 半径 {source.radius}m</small>
        </button>)}</div>
        {matches.length > limit && <button className="button" onClick={() => setLimit(value => value + 50)}>範囲の詳細をさらに50件表示</button>}
      </>}
    </section>}
  </div>
}
