import { memo, useEffect, useId, useMemo, useRef, useState } from 'react'
import type { ChangeEvent, KeyboardEvent } from 'react'
import { STATUS_TYPES } from '../data/statusTypes.generated'
import { asList, asObject, classBonus, downloadCsv, equipmentRows, formatNumber, formatEquipmentRange as formatRange, normalizeStatus, numeric, plainName } from '../data/masterAnalytics'
import type { AnalyticsCatalog, GrowthSimulation } from '../data/masterAnalytics'
import { sortEquipment } from '../data/equipmentSort'
import { createMasterNameMatcher } from '../data/masterDataFilters'
import type { EquipmentSortDirection, EquipmentSortKey } from '../data/equipmentSort'
import './masterAnalytics.css'
import { MinecraftText } from './MinecraftText'
import { BalanceLab } from './BalanceLab'

const colors = ['#65c7f7', '#f5ad6b', '#99d9a5', '#de9fe9', '#f0d278', '#fa8d99', '#8bd9d4', '#a9b6fa', '#dfb289', '#e2e8f0', '#8bb592', '#d6a9cb']
const statusLabel = (id: string) => STATUS_TYPES.find(status => status.id === id)?.displayName ?? id

async function read<T>(url: string, signal?: AbortSignal): Promise<T> {
  const response = await fetch(url, { signal })
  const payload = await response.json()
  if (!response.ok) throw new Error(payload.title ?? payload.message ?? `HTTP ${response.status}`)
  return payload as T
}

interface ChartSeries { name: string; displayName?: string; values: { x: number; y: number }[] }
const LineChart = memo(function LineChart({ series, yLabel, xLabel }: { series: ChartSeries[]; yLabel: string; xLabel: string }) {
  const width = 950, height = 360, left = 75, top = 25, right = 20, bottom = 50
  const all = series.flatMap(item => item.values)
  const minX = all.length ? Math.min(...all.map(point => point.x)) : 1
  const maxX = Math.max(minX + 1, ...all.map(point => point.x))
  const minY = Math.min(0, ...all.map(point => point.y)), maxY = Math.max(1, ...all.map(point => point.y))
  const x = (value: number) => left + (value - minX) / (maxX - minX) * (width - left - right)
  const y = (value: number) => top + (maxY - value) / (maxY - minY) * (height - top - bottom)
  return <div className="analytics-chart">
    <svg viewBox={`0 0 ${width} ${height}`} role="img" aria-label={`${xLabel}に対する${yLabel}の比較グラフ`}>
      <title>{`${xLabel}に対する${yLabel}。数値は下の表で確認できます。`}</title>
      {Array.from({ length: 6 }, (_, index) => {
        const value = minY + (maxY - minY) * index / 5
        return <g key={index}><line className="chart-grid" x1={left} x2={width - right} y1={y(value)} y2={y(value)} /><text x={left - 10} y={y(value) + 4} textAnchor="end">{formatNumber(value)}</text></g>
      })}
      {Array.from({ length: 6 }, (_, index) => {
        const value = minX + (maxX - minX) * index / 5
        return <text key={index} x={x(value)} y={height - 23} textAnchor="middle">{formatNumber(value)}</text>
      })}
      {series.map((item, index) => <path key={item.name} fill="none" stroke={colors[index % colors.length]} strokeWidth="2.4" d={item.values.map((point, pointIndex) => `${pointIndex === 0 ? 'M' : 'L'}${x(point.x)},${y(point.y)}`).join(' ')}><title>{item.name}</title></path>)}
      <text x={left} y={14}>{yLabel}</text><text x={width - right} y={height - 2} textAnchor="end">{xLabel}</text>
    </svg>
    <div className="chart-legend">{series.map((item, index) => <span key={item.name}><i style={{ background: colors[index % colors.length] }} /><MinecraftText value={item.displayName ?? item.name} /></span>)}</div>
  </div>
})

export function MasterAnalytics() {
  const [catalog, setCatalog] = useState<AnalyticsCatalog | null>(null)
  const [catalogStatus, setCatalogStatus] = useState<'loading' | 'ready' | 'error'>('loading')
  const catalogRequest = useRef<AbortController | null>(null)
  const growthRequest = useRef<AbortController | null>(null)
  const [error, setError] = useState('')
  const [revision, setRevision] = useState(0)
  const [mode, setMode] = useState<'equipment' | 'growth' | 'lab'>('equipment')
  const [labOpened, setLabOpened] = useState(false)
  const openLab = () => { setLabOpened(true); setMode('lab') }
  function moveTab(event: KeyboardEvent<HTMLButtonElement>) {
    if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
    event.preventDefault()
    const tabs = Array.from(event.currentTarget.parentElement!.querySelectorAll<HTMLButtonElement>('[role="tab"]'))
    const index = tabs.indexOf(event.currentTarget)
    const next = event.key === 'Home' ? 0 : event.key === 'End' ? tabs.length - 1 : (index + (event.key === 'ArrowRight' ? 1 : -1) + tabs.length) % tabs.length
    tabs[next].focus()
    tabs[next].click()
  }
  const [query, setQuery] = useState('')
  const equipmentSearchHelpId = useId()
  const bulkSelectionHelpId = useId()
  const [slot, setSlot] = useState('')
  const [tag, setTag] = useState('')
  const [level, setLevel] = useState(10)
  const [band, setBand] = useState(10)
  const [enhance, setEnhance] = useState(0)
  const [stat, setStat] = useState('MELEE_ATTACK')
  const [sortKey, setSortKey] = useState<EquipmentSortKey>('default')
  const [sortDirection, setSortDirection] = useState<EquipmentSortDirection>('desc')
  const isStatSort = sortKey === 'min' || sortKey === 'max'
  const sortStatus = isStatSort ? stat : ''
  const [selectedPaths, setSelectedPaths] = useState<string[]>([])
  const [selectedOnly, setSelectedOnly] = useState(false)
  const selectedViewButton = useRef<HTMLButtonElement>(null)
  const [page, setPage] = useState(0)
  const [uuid, setUuid] = useState('00000000-0000-0000-0000-000000000001')
  const [startPlayer, setStartPlayer] = useState(1)
  const [startClass, setStartClass] = useState(1)
  const [maximum, setMaximum] = useState(100)
  const [classIds, setClassIds] = useState<string[]>(['adventurer', 'swordsman', 'hunter', 'mage'])
  const [classQuery, setClassQuery] = useState('')
  const [classSelectedOnly, setClassSelectedOnly] = useState(false)
  const classSearchInput = useRef<HTMLInputElement>(null)
  const classSearchHelpId = useId()
  const [simulation, setSimulation] = useState<GrowthSimulation | null>(null)
  const [growing, setGrowing] = useState(false)
  const [growthStat, setGrowthStat] = useState('MAX_HEALTH')
  const [growthView, setGrowthView] = useState<'level' | 'experience' | 'bonus'>('level')
  const [inspect, setInspect] = useState(10)
  useEffect(() => {
    const controller = new AbortController()
    catalogRequest.current = controller
    setCatalogStatus('loading')
    setError('')
    void read<AnalyticsCatalog>('/api/master-analytics/catalog', controller.signal).then(result => {
      if (controller.signal.aborted) return
      setCatalog(result); setCatalogStatus('ready')
    }).catch(reason => {
      if (controller.signal.aborted) return
      setError(String(reason)); setCatalogStatus('error')
    })
    return () => {
      controller.abort()
      growthRequest.current?.abort()
      growthRequest.current = null
    }
  }, [revision])
  const equipment = catalog?.equipment
  const classes = catalog?.classes
  const matchesClassName = useMemo(() => createMasterNameMatcher(classQuery), [classQuery])
  const visibleClasses = useMemo(() => (classes ?? []).filter(master =>
    (!classSelectedOnly || classIds.includes(String(master.content.id))) &&
    matchesClassName(String(master.content.name ?? ''), String(master.content.id), master.path)),
  [classes, classSelectedOnly, classIds, matchesClassName])
  const catalogClassIds = useMemo(() => new Set((classes ?? []).map(master => String(master.content.id))), [classes])
  const missingClassIds = useMemo(() => classIds.filter(id => !catalogClassIds.has(id)), [classIds, catalogClassIds])
  const hiddenSelectedClasses = useMemo(() => {
    const visibleIds = new Set(visibleClasses.map(master => String(master.content.id)))
    return classIds.filter(id => catalogClassIds.has(id) && !visibleIds.has(id)).length
  }, [visibleClasses, classIds, catalogClassIds])
  const names = useMemo(() => new Map((equipment ?? []).map(master => [master.path, String(master.content.name ?? '')])), [equipment])
  const rows = useMemo(() => equipmentRows(equipment ?? [], enhance), [equipment, enhance])
  const defaultRows = useMemo(() => [...rows].sort((a, b) => a.level - b.level || a.id.localeCompare(b.id)), [rows])
  const slots = useMemo(() => [...new Set(rows.map(row => row.slot))].sort(), [rows])
  const tags = useMemo(() => [...new Set(rows.map(row => row.tag))].sort(), [rows])
  const matchesEquipmentName = useMemo(() => createMasterNameMatcher(query), [query])
  const filtered = useMemo(() => {
    return defaultRows.filter(row => Math.abs(row.level - level) <= band && (!slot || row.slot === slot) && (!tag || row.tag === tag) && matchesEquipmentName(row.name, row.id, row.path))
  }, [defaultRows, level, band, slot, tag, matchesEquipmentName])
  const rowsByPath = useMemo(() => new Map(rows.map(row => [row.path, row])), [rows])
  const selectedPathSet = useMemo(() => new Set(selectedPaths), [selectedPaths])
  const selectedRows = useMemo(() => selectedPaths.flatMap(path => {
    const row = rowsByPath.get(path)
    return row ? [row] : []
  }), [rowsByPath, selectedPaths])
  const filteredPaths = useMemo(() => new Set(filtered.map(row => row.path)), [filtered])
  const outsideCount = useMemo(() => selectedRows.filter(row => !filteredPaths.has(row.path)).length, [selectedRows, filteredPaths])
  const matchingSelectedCount = selectedRows.length - outsideCount
  const missingCount = selectedPaths.length - selectedRows.length
  const sortedFiltered = useMemo(() => sortEquipment(filtered, sortKey, sortDirection, sortStatus), [filtered, sortKey, sortDirection, sortStatus])
  const sortedSelected = useMemo(() => sortEquipment(selectedRows, sortKey, sortDirection, sortStatus), [selectedRows, sortKey, sortDirection, sortStatus])
  const active = selectedPaths.length || selectedOnly ? sortedSelected : sortedFiltered
  const tableRows = selectedOnly ? sortedSelected : sortedFiltered
  const pageCount = Math.max(1, Math.ceil(tableRows.length / 50))
  const currentPage = Math.min(page, pageCount - 1)
  const visibleRows = useMemo(() => tableRows.slice(currentPage * 50, (currentPage + 1) * 50), [tableRows, currentPage])
  const filteredStatuses = useMemo(() => [...new Set(filtered.flatMap(row => Object.keys(row.stats)))].sort(), [filtered])
  const selectedStatuses = useMemo(() => [...new Set(selectedRows.flatMap(row => Object.keys(row.stats)))].sort(), [selectedRows])
  const statuses = selectedPaths.length || selectedOnly ? selectedStatuses : filteredStatuses
  const tableStatuses = selectedOnly ? selectedStatuses : filteredStatuses
  const scale = useMemo(() => Math.max(1, ...active.map(row => Math.max(Math.abs(row.stats[stat]?.min ?? 0), Math.abs(row.stats[stat]?.max ?? 0)))), [active, stat])
  const growthStatuses = useMemo(() => [...new Set((classes ?? []).flatMap(master => [...asList(master.content.baseStats), ...asList(master.content.growthPerLevel)].map(asObject).map(entry => normalizeStatus(entry.status))))].sort(), [classes])

  function selectEquipment(event: ChangeEvent<HTMLInputElement>, path: string) {
    const checked = event.currentTarget.checked
    if (!checked && selectedOnly) {
      // The unchecked row disappears; keep keyboard focus on a surviving control.
      const checkboxes = Array.from(event.currentTarget.closest('tbody')!.querySelectorAll<HTMLInputElement>('input[type="checkbox"]'))
      const index = checkboxes.indexOf(event.currentTarget)
      const nextFocus = checkboxes[index + 1] ?? checkboxes[index - 1] ?? selectedViewButton.current
      nextFocus?.focus()
    }
    setSelectedPaths(current => checked ? current.includes(path) ? current : [...current, path] : current.filter(value => value !== path))
  }

  function selectClass(event: ChangeEvent<HTMLInputElement>, id: string) {
    const checked = event.currentTarget.checked
    if (!checked && classSelectedOnly) {
      const checkboxes = Array.from(event.currentTarget.closest('fieldset')!.querySelectorAll<HTMLInputElement>('input[type="checkbox"]'))
      const index = checkboxes.indexOf(event.currentTarget)
      ;(checkboxes[index + 1] ?? checkboxes[index - 1] ?? classSearchInput.current)?.focus()
    }
    setClassIds(current => checked ? current.includes(id) || current.length >= 12 ? current : [...current, id] : current.filter(value => value !== id))
  }

  function updateMatchingSelection(adding: boolean) {
    if (adding ? matchingSelectedCount === filtered.length : matchingSelectedCount === 0) return
    setSelectedPaths(current => {
      if (!adding) return current.filter(path => !filteredPaths.has(path))
      const selected = new Set(current)
      const next = [...current]
      for (const row of sortedFiltered) {
        if (selected.has(row.path)) continue
        selected.add(row.path)
        next.push(row.path)
      }
      return next
    })
    setSelectedOnly(true)
    setPage(0)
    selectedViewButton.current?.focus()
  }

  function reloadMasters() {
    catalogRequest.current?.abort()
    growthRequest.current?.abort()
    growthRequest.current = null
    setGrowing(false); setSimulation(null); setCatalogStatus('loading')
    setRevision(value => value + 1)
  }

  async function generate() {
    if (catalogStatus !== 'ready' || growing || !classIds.length) return
    const controller = new AbortController()
    growthRequest.current?.abort()
    growthRequest.current = controller
    setGrowing(true); setError('')
    try {
      const params = new URLSearchParams({ uuid, maxPlayerLevel: String(maximum), startPlayerLevel: String(startPlayer), startClassLevel: String(startClass), classIds: classIds.join(',') })
      const result = await read<GrowthSimulation>(`/api/master-analytics/growth?${params}`, controller.signal)
      if (growthRequest.current !== controller || controller.signal.aborted) return
      setSimulation(result); setInspect(Math.max(startPlayer, Math.min(maximum, inspect)))
    } catch (reason) {
      if (growthRequest.current === controller && !controller.signal.aborted) setError(String(reason))
    } finally {
      if (growthRequest.current === controller && !controller.signal.aborted) {
        growthRequest.current = null
        setGrowing(false)
      }
    }
  }
  const point = useMemo(() => simulation?.points.find(value => value.playerLevel === inspect) ?? simulation?.points[0], [simulation, inspect])
  const graph = useMemo(() => simulation?.classes.map(definition => ({
    name: plainName(definition.name),
    displayName: definition.name,
    values: simulation.points.map(item => {
      const current = item.classes.find(value => value.id === definition.id)!
      const master = classes?.find(value => value.content.id === definition.id)
      return { x: item.playerLevel, y: growthView === 'level' ? current.level : growthView === 'experience' ? current.totalExperience : master ? classBonus(master, growthStat, current.level) : 0 }
    }),
  })) ?? [], [simulation, classes, growthView, growthStat])

  return <section className="master-analytics">
    <header className="analytics-header"><div><h1>比較・成長グラフ</h1><p>保存済みのFilebaseから計算。変更後は再読込してください。</p></div><button className="button" onClick={reloadMasters}>マスター再読込</button></header>
    <div role="tablist" aria-label="分析の種類" className="analytics-tabs"><button id="analytics-equipment-tab" role="tab" aria-controls="analytics-equipment-panel" aria-selected={mode === 'equipment'} tabIndex={mode === 'equipment' ? 0 : -1} className="button" onKeyDown={moveTab} onClick={() => setMode('equipment')}>装備を比較</button><button id="analytics-growth-tab" role="tab" aria-controls="analytics-growth-panel" aria-selected={mode === 'growth'} tabIndex={mode === 'growth' ? 0 : -1} className="button" onKeyDown={moveTab} onClick={() => setMode('growth')}>クラス成長</button><button id="analytics-lab-tab" role="tab" aria-controls="analytics-lab-panel" aria-selected={mode === 'lab'} tabIndex={mode === 'lab' ? 0 : -1} className="button" onKeyDown={moveTab} onClick={openLab}>統合バランスラボ</button></div>
    {labOpened && <div id="analytics-lab-panel" role="tabpanel" aria-labelledby="analytics-lab-tab" hidden={mode !== 'lab'}><BalanceLab /></div>}
    {mode !== 'lab' && (!catalog ? <p role={error ? 'alert' : 'status'}>{error || 'マスターを読み込んでいます…'}</p> : <>
    {error && <p role="alert" className="analytics-error">{error}</p>}
    {catalog.diagnostics.length > 0 && <details><summary>読み込めないマスター {catalog.diagnostics.length}件</summary>{catalog.diagnostics.map(item => <p key={item.path}>{item.path}: {item.message}</p>)}</details>}
    {mode === 'equipment' ? <div id="analytics-equipment-panel" role="tabpanel" aria-labelledby="analytics-equipment-tab" className="analytics-workspace">
      <section className="analytics-panel analytics-settings" aria-labelledby="equipment-settings-heading">
      <div className="analytics-section-heading"><h2 id="equipment-settings-heading">比較条件</h2><span className="analytics-badge">保存済みマスター</span></div>
      <p className="analytics-note">条件で候補を探し、チェックして比較に追加します。選択した装備は条件を変えても比較対象に残ります。</p>
      <div className="analytics-controls">
        <div className="analytics-field-wide"><label>名前・ID<input value={query} aria-describedby={equipmentSearchHelpId} onChange={event => { setQuery(event.target.value); setPage(0) }} placeholder="例: 銀 剣" /></label><p id={equipmentSearchHelpId} className="analytics-search-help">名前・ID・パスを検索します。空白で区切ると、すべての語で絞り込みます。</p></div>
        <label>スロット<select value={slot} onChange={event => { setSlot(event.target.value); setPage(0) }}><option value="">すべて</option>{slots.map(value => <option key={value}>{value}</option>)}</select></label>
        <label>装備タグ<select value={tag} onChange={event => { setTag(event.target.value); setPage(0) }}><option value="">すべて</option>{tags.map(value => <option key={value}>{value}</option>)}</select></label>
        <label>要求Lvの中心<input type="number" min="0" value={level} onChange={event => { setLevel(Math.max(0, numeric(event.target.value))); setPage(0) }} /></label>
        <label>前後のLv幅<input type="number" min="0" value={band} onChange={event => { setBand(Math.max(0, numeric(event.target.value))); setPage(0) }} /></label>
        <label>強化Lv<input type="number" min="0" max="100" value={enhance} onChange={event => { setEnhance(Math.max(0, Math.min(100, numeric(event.target.value)))); if (isStatSort) setPage(0) }} /></label>
        <label className="analytics-field-wide">グラフのステータス<select value={stat} onChange={event => { setStat(event.target.value); if (isStatSort) setPage(0) }}>{[...new Set([stat, ...statuses])].map(value => <option key={value} value={value}>{statusLabel(value)} ({value})</option>)}</select></label>
      </div>
      <div className="analytics-actions" role="group" aria-label="条件一致の一括選択" aria-describedby={bulkSelectionHelpId}>
        <button type="button" className="button" disabled={matchingSelectedCount === filtered.length} onClick={() => updateMatchingSelection(true)}>条件一致を比較に追加（{filtered.length - matchingSelectedCount}件）</button>
        <button type="button" className="button" disabled={matchingSelectedCount === 0} onClick={() => updateMatchingSelection(false)}>条件一致の選択を解除（{matchingSelectedCount}件）</button>
      </div>
      <p id={bulkSelectionHelpId} className="analytics-note">全ページの絞り込み結果 {filtered.length}件が対象です。ボタンの件数は追加・解除する件数です。条件外の選択は保持し、操作後は「選択した装備」を表示します。</p>
      <details className="analytics-explanation"><summary>比較の計算方法と対象範囲</summary><p className="analytics-note">マスターの下限〜上限を比較します。強化は上限Lvまでの差分を累積し、同じ装備のFLAT合計 × 最後に採用されるSCALARを計算します。強化SCALARがある場合は基礎SCALARを置き換えます。SCALARだけのステータスは効果を持ちません。個体の乱数・エンチャント・ルーン・セット・超越・装備可否による無効化は含みません。</p></details>
      </section>
      <section className="analytics-panel analytics-results" aria-labelledby="equipment-results-heading">
      <div className="analytics-section-heading"><div><h2 id="equipment-results-heading">比較結果</h2><p className="analytics-result-count" role="status">{active.length}件を比較 <span>／ 条件一致 {filtered.length}件</span></p></div><div className="analytics-actions"><button className="button" disabled={!selectedPaths.length} onClick={() => { setSelectedPaths([]); setSelectedOnly(false); setPage(0) }}>選択を解除</button><button className="button" disabled={!active.length} onClick={() => downloadCsv('equipment-comparison.csv', [['ID', '名前', 'スロット', 'タグ', '要求Lv', '進行度', ...statuses.flatMap(id => [`${id} 下限`, `${id} 上限`])], ...active.map(row => [row.id, row.name, row.slot, row.tag, row.level, row.progression, ...statuses.flatMap(id => [row.stats[id]?.min, row.stats[id]?.max])])])}>比較表をCSV出力</button></div></div>
      {selectedPaths.length > 0 && <p className="analytics-note" role="status">選択中: {selectedRows.length}件（現在の条件外 {outsideCount}件）。「選択した装備」の一覧から個別に解除できます。</p>}
      {missingCount > 0 && <div className="analytics-selection-warning" role="status"><p>選択した装備のうち {missingCount}件が現在のマスターに見つかりません。比較とCSVには含めません。</p><button className="button" onClick={() => { setSelectedPaths(current => current.filter(path => rowsByPath.has(path))); setPage(0) }}>見つからない選択を解除</button></div>}
      <div className="analytics-controls" role="group" aria-label="比較結果の並び順">
        <label>並び順<select value={sortKey} onChange={event => { setSortKey(event.target.value as EquipmentSortKey); setPage(0) }}>
          <option value="default">標準</option>
          <option value="name">名前</option><option value="id">ID</option><option value="level">要求Lv</option><option value="progression">進行度</option>
          <option value="min">{statusLabel(stat)}の下限</option><option value="max">{statusLabel(stat)}の上限</option>
        </select></label>
        <label>並べる方向<select value={sortDirection} disabled={sortKey === 'default'} onChange={event => { setSortDirection(event.target.value as EquipmentSortDirection); setPage(0) }}><option value="desc">大きい順・降順</option><option value="asc">小さい順・昇順</option></select></label>
      </div>
      <p className="analytics-note">並び順は候補表・選択表・バー・CSVに共通です。標準は候補が要求Lv→ID順、選択した装備が選択順です。下限・上限は「グラフのステータス」を使い、未定義の値は末尾に表示します。同じ値は標準の順序を保ちます。</p>
      <h3>{statusLabel(stat)}の範囲比較</h3>
      <p className="analytics-note">バーは比較対象の先頭30件を表示します。表は50件ずつ表示し、CSVには比較対象すべてを出力します。</p>
      <div className="equipment-bars" aria-label={`${statusLabel(stat)}の範囲比較`}>{active.filter(row => row.stats[stat]).slice(0, 30).map(row => <div key={row.path} className="equipment-bar"><span title={row.id}><MinecraftText value={names.get(row.path) || row.name} /></span><div><i style={{ width: `${Math.max(Math.abs(row.stats[stat].min), Math.abs(row.stats[stat].max)) / scale * 100}%` }} /></div><strong>{formatRange(row.stats[stat])}</strong></div>)}</div>
      {!active.some(row => row.stats[stat]) && <p className="analytics-empty">このステータスを持つ比較対象がありません。条件やステータスを変更してください。</p>}
      <h3>装備の比較表</h3>
      <div className="analytics-actions analytics-list-mode" role="group" aria-label="装備一覧の表示">
        <button className="button" aria-pressed={!selectedOnly} onClick={() => { setSelectedOnly(false); setPage(0) }}>条件に一致する装備（{filtered.length}件）</button>
        <button ref={selectedViewButton} className="button" aria-pressed={selectedOnly} onClick={() => { setSelectedOnly(true); setPage(0) }}>選択した装備（{selectedRows.length}件）</button>
      </div>
      <div className="analytics-table-wrap" role="region" aria-label="装備の比較表" tabIndex={0}><table><caption>{selectedOnly ? '選択した装備を指定の並び順で表示。標準では選択順です。チェックを外すと比較から解除します。' : '条件に一致する候補を表示。チェックして比較に追加できます。未選択の場合は条件に一致する全装備を比較します。'}</caption><thead><tr><th>選択</th><th>装備 / ID</th><th>スロット / タグ</th><th>要求Lv / 進行度</th><th>必要職</th>{tableStatuses.map(id => <th title={id} key={id}>{statusLabel(id)}</th>)}</tr></thead><tbody>{visibleRows.map(row => <tr key={row.path} className={selectedPathSet.has(row.path) ? 'selected' : ''}><td><input type="checkbox" aria-label={`${row.name}を比較に選択`} checked={selectedPathSet.has(row.path)} onChange={event => selectEquipment(event, row.path)} /></td><th scope="row"><span><MinecraftText value={names.get(row.path) || row.name} /></span><small>{row.id} · {row.rarity}</small><details><summary>定義を確認</summary><code>{row.path}</code>{row.rawStats.map((definition, index) => <div key={index}>{definition}</div>)}</details></th><td>{row.slot}<small>{row.tag} · {row.hand}</small></td><td>{row.level}<small>進行度 {row.progression ?? '未定義'}</small></td><td>{row.requiredClasses || '制限なし'}</td>{tableStatuses.map(id => <td key={id}>{formatRange(row.stats[id])}</td>)}</tr>)}</tbody></table>{!tableRows.length && <p className="analytics-empty">{selectedOnly ? '表示できる選択装備がないため比較は0件です。「条件に一致する装備」から比較に追加してください。' : '条件に一致する装備がありません。名前・レベル幅・スロットなどの条件を変更してください。'}</p>}</div>
      <div className="analytics-actions"><button className="button" disabled={currentPage === 0} onClick={() => setPage(currentPage - 1)}>前の50件</button><span>{currentPage + 1} / {pageCount}ページ</span><button className="button" disabled={currentPage + 1 >= pageCount} onClick={() => setPage(currentPage + 1)}>次の50件</button></div>
      </section>
    </div> : <div id="analytics-growth-panel" role="tabpanel" aria-labelledby="analytics-growth-tab" className="analytics-workspace">
      <section className="analytics-panel analytics-settings" aria-labelledby="growth-settings-heading">
      <div className="analytics-section-heading"><h2 id="growth-settings-heading">成長の計算条件</h2></div>
      <div className="analytics-compute"><span>{classIds.length} / 12職業を選択</span><button className="button primary" disabled={catalogStatus !== 'ready' || growing || !classIds.length} onClick={() => void generate()}>{growing ? '計算中…' : 'グラフを計算'}</button></div>
      {catalogStatus !== 'ready' && <p className="analytics-note" role="status">{catalogStatus === 'loading' ? 'マスターを再読込しています。完了後に計算できます。' : 'マスターを取得できませんでした。再読込が成功すると計算できます。'}</p>}
      <p className="analytics-note">条件を設定し「グラフを計算」で結果を更新します。</p>
      <div className="analytics-controls">
        <label className="analytics-field-wide">アカウントUUID<input value={uuid} onChange={event => setUuid(event.target.value)} className="uuid-input" /></label>
        <label>開始プレイヤーLv<input type="number" min="1" max="200" value={startPlayer} onChange={event => setStartPlayer(numeric(event.target.value, 1))} /></label>
        <label>開始クラスLv<input type="number" min="1" max="1000" value={startClass} onChange={event => setStartClass(numeric(event.target.value, 1))} /></label>
        <label>表示上限プレイヤーLv<input type="number" min="1" max="200" value={maximum} onChange={event => setMaximum(numeric(event.target.value, 100))} /></label>
      </div>
      <div className="analytics-controls"><label className="analytics-field-wide">職業を検索<input ref={classSearchInput} value={classQuery} aria-describedby={classSearchHelpId} placeholder="名前・ID・パス" onChange={event => setClassQuery(event.target.value)} /></label></div>
      <p id={classSearchHelpId} className="analytics-search-help">空白区切りですべての語を含む職業を検索します。表示条件を変えても選択は保持されます。</p>
      <div className="analytics-actions analytics-list-mode" role="group" aria-label="職業一覧の表示">
        <button className="button" aria-pressed={classSelectedOnly} onClick={() => setClassSelectedOnly(value => !value)}>選択済みだけ表示</button>
        <button className="button" disabled={!classQuery && !classSelectedOnly} onClick={() => { setClassQuery(''); setClassSelectedOnly(false); classSearchInput.current?.focus() }}>職業の条件を解除</button>
      </div>
      <p className="analytics-note" aria-live="polite">表示 {visibleClasses.length} / {catalog.classes.length} 職業 · 条件外の選択 {hiddenSelectedClasses} 件</p>
      {missingClassIds.length > 0 && <p className="analytics-note">現在のカタログにない選択ID: {missingClassIds.join(', ')}。選択は保持しています。</p>}
      <fieldset className="class-selection"><legend>比較する職業（最大12件）</legend>{visibleClasses.map(master => { const id = String(master.content.id); return <label key={master.path} title={`解放プレイヤーLv ${numeric(master.content.unlockLevel, 1)} / 必要経験値係数 ${numeric(master.content.expRate, 100)} / 最大レベル ${numeric(master.content.maxLevel, 100)}`}><input type="checkbox" checked={classIds.includes(id)} disabled={!classIds.includes(id) && classIds.length >= 12} onChange={event => selectClass(event, id)} /><MinecraftText value={String(master.content.name ?? '')} /><small>{id}</small></label> })}{!visibleClasses.length && <p className="analytics-note class-selection-empty">表示条件に一致する{classSelectedOnly ? '選択済みの' : ''}職業候補はありません。検索・表示条件を変えても選択は解除されません。</p>}</fieldset>
      <details className="analytics-explanation"><summary>育成シナリオの前提</summary><p className="analytics-note">入力変更後は「グラフを計算」で反映します。開始Lvは到達直後として計算します。通常育成・転生なし、同じEXPをプレイヤーと1職へ加算する仮定です。各職の曲線はそれぞれ独立した育成シナリオです。</p></details>
      </section>
      <section className="analytics-panel analytics-results" aria-labelledby="growth-results-heading" aria-busy={growing}>
      <div className="analytics-section-heading"><h2 id="growth-results-heading">成長の計算結果</h2>{simulation && <span className="analytics-badge">{simulation.classes.length}職業を比較</span>}</div>
      {!simulation && <div className="analytics-empty"><h3>職業ごとの成長を比較</h3><p>計算条件を設定して「グラフを計算」を押すと、グラフと指定レベルの数値表を表示します。</p></div>}
      {simulation && <>
        <div className="analytics-controls"><label>グラフの種類<select value={growthView} onChange={event => setGrowthView(event.target.value as typeof growthView)}><option value="level">プレイヤーLv → クラスLv</option><option value="experience">プレイヤーLv → クラス累積EXP</option><option value="bonus">プレイヤーLv → クラスのステータス補正</option></select></label>{growthView === 'bonus' && <label>ステータス<select value={growthStat} onChange={event => setGrowthStat(event.target.value)}>{[...new Set([growthStat, ...growthStatuses])].map(id => <option key={id} value={id}>{statusLabel(id)} ({id})</option>)}</select></label>}</div>
        <LineChart series={graph} xLabel="プレイヤーLv" yLabel={growthView === 'level' ? 'クラスLv' : growthView === 'experience' ? 'クラス累積EXP' : `${statusLabel(growthStat)}（クラス補正のみ）`} />
        <label className="inspect-level">確認するプレイヤーLv: {inspect}<input type="range" min={simulation.startPlayerLevel} max={simulation.points.at(-1)?.playerLevel} step="1" value={inspect} onChange={event => setInspect(Number(event.target.value))} /></label>
        {point && <div className="analytics-table-wrap" role="region" aria-label="指定レベルの成長表" tabIndex={0}><table><caption>プレイヤーLv {point.playerLevel} · 累積EXP {formatNumber(point.playerTotalExperience)}</caption><thead><tr><th>職業</th><th>クラスLv / 上限</th><th>累積クラスEXP</th><th>次Lvまで</th><th>必要経験値係数</th><th>{statusLabel(growthStat)}補正</th><th>1Lvの成長</th></tr></thead><tbody>{simulation.classes.map(definition => { const value = point.classes.find(item => item.id === definition.id)!; const master = catalog.classes.find(item => item.content.id === definition.id); return <tr key={definition.id}><th scope="row"><MinecraftText value={definition.name} /><small>{definition.id}</small></th><td>{value.level} / {definition.maxLevel}</td><td>{formatNumber(value.totalExperience)}</td><td>{value.level === definition.maxLevel ? '上限到達' : formatNumber(value.nextLevelExperience)}</td><td>{definition.expRate}</td><td>{master ? formatNumber(classBonus(master, growthStat, value.level)) : '—'}</td><td>{master ? formatNumber(numeric(asList(master.content.growthPerLevel).map(asObject).find(stat => normalizeStatus(stat.status) === growthStat)?.value)) : '—'}</td></tr> })}</tbody></table></div>}
        <button className="button" onClick={() => downloadCsv('class-growth.csv', [['プレイヤーLv', '累積プレイヤーEXP', ...simulation.classes.map(definition => `${definition.id} クラスLv`)], ...simulation.points.map(item => [item.playerLevel, item.playerTotalExperience, ...simulation.classes.map(definition => item.classes.find(value => value.id === definition.id)?.level)])])}>成長表をCSV出力</button>
        <details className="analytics-sources"><summary>計算の根拠と前提</summary><p>{simulation.assumption}</p><p>プレイヤー必要EXP = 500 + Lv²×120 + floor(Lv/10)×850 + 8段階wave + 5Lvごとの加算 + UUID由来の加算。クラス必要EXP = round((45 + Lv²×8 + 10Lvごとの加算) × max(expRate,10)/100)。クラス補正 = baseStats + growthPerLevel × (クラスLv−1)。</p>{simulation.sources.map(source => <code key={source}>{source}</code>)}<p>アカウントUUID: {simulation.accountUuid}。EXPERIENCE_GAIN_RATEは両方へ加算する前に適用されるため、この累積EXP対レベルの比較には影響しません。</p></details>
      </>}
      </section>
    </div>}
    </>)}
  </section>
}
