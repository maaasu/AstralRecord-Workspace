import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { worldPlacementApi, type PlacementKind, type PlacementSnapshot, type WorldPlacement } from '../api/worldPlacementApi'
import { cellAtWorld, type PlanningSnapshot } from '../data/spawnerPlanning'
import { MinecraftText } from './MinecraftText'
import { spawnerMasterNumbers } from '../data/spawnerMapStyle'
import { applyPlacementDrafts, parsePlacementNumber, placementChangeEntries, placementSelectionScope, removePlacements, revertPlacementChange, samePlacement, setPlacementsEnabled, type PlacementNumericDrafts, type PlacementNumericField } from '../state/worldPlacementEditing'
import './worldPlacementEditor.css'
import { PlacementChangeReview } from './PlacementChangeReview'

export interface PlacementCatalog { spawners: { id: string; name: string; radiusMeters: number | null; levels: number[] }[]; npcs: { id: string; name: string }[]; worlds: { id: string; name: string; baseWorldPath: string }[] }
export interface PlacementPoint { key: number; x: number; y: number | null; z: number }
interface Props {
  editing?: boolean
  map?: ReactNode
  snapshot: PlanningSnapshot
  point: PlacementPoint | null
  selectedId: string | null
  selectionKey?: number
  onSelected: (id: string | null, location?: { x: number; z: number }) => void
  onPlacements: (placements: WorldPlacement[], catalog: PlacementCatalog, ready: boolean) => void
  placing: boolean
  onPlacing: (placing: boolean) => void
  moving?: boolean
  onMoving?: (moving: boolean) => void
  onBusyChange?: (busy: boolean) => void
  onDirtyChange?: (dirty: boolean) => void
}
const emptySelection: ReadonlySet<string> = new Set()
const emptyCatalog: PlacementCatalog = { spawners: [], npcs: [], worlds: [] }
const readCatalog = async (): Promise<PlacementCatalog> => {
  const response = await fetch('/api/world-placements/catalog'), payload = await response.json()
  if (!response.ok) throw new Error(payload.title ?? '配置マスターを読み込めません。')
  return payload as PlacementCatalog
}
const clean = (value: string) => value.replace(/[&§][0-9a-fk-or]/gi, '')
const fresh = (kind: PlacementKind, worldId: string, worldName: string, templateId: string | null, x: number, y: number, z: number): WorldPlacement => ({
  placementId: crypto.randomUUID(), kind, worldId, worldName, templateId, x, y, z, yaw: 0, pitch: 0, enabled: true,
  waystone: kind === 'waystone' ? { id: '', name: '', lockEnabled: false, unlockGold: 0, icon: 'LODESTONE', createdAt: new Date().toISOString(), createdBy: 'master-data-editor' } : null,
})

export function WorldPlacementEditor({ editing = true, map, snapshot, point, selectedId, selectionKey = 0, onSelected, onPlacements, placing, onPlacing, moving = false, onMoving = () => {}, onBusyChange = () => {}, onDirtyChange = () => {} }: Props) {
  const [base, setBase] = useState<PlacementSnapshot | null>(null)
  const [history, setHistory] = useState<{ past: WorldPlacement[][]; working: WorldPlacement[]; future: WorldPlacement[][] }>({ past: [], working: [], future: [] })
  const { working } = history
  const [drafts, setDrafts] = useState<PlacementNumericDrafts>({})
  const hasDrafts = Object.keys(drafts).length > 0
  const invalidDraftId = Object.keys(drafts).find(id => Object.entries(drafts[id]).some(([field, raw]) => parsePlacementNumber(raw!, field as PlacementNumericField) === null))
  const changeEntries = useMemo(() => placementChangeEntries(base?.placements ?? [], working), [base, working])
  const changes = useMemo(() => ({ added: changeEntries.filter(item => item.kind === 'added').length, changed: changeEntries.filter(item => item.kind === 'changed').length, deleted: changeEntries.filter(item => item.kind === 'deleted').length }), [changeEntries])
  const dirty = changes.added + changes.changed + changes.deleted > 0 || hasDrafts
  const setWorking = (next: WorldPlacement[]) => setHistory(current => current.working === next || current.working.length === next.length && current.working.every((item, index) => samePlacement(item, next[index])) ? current : ({ past: [...current.past.slice(-99), current.working], working: next, future: [] }))
  const resetWorking = (next: WorldPlacement[]) => { setHistory({ past: [], working: next, future: [] }); setDrafts({}); setSelectedOnly(false); setBulkSelection({ worldId: snapshot.manifest.worldId, ids: new Set() }) }
  const [catalog, setCatalog] = useState<PlacementCatalog>(emptyCatalog)
  const [busy, setBusy] = useState(false)
  const busyRef = useRef(false)
  const inspector = useRef<HTMLDivElement>(null)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [kind, setKind] = useState<PlacementKind>('spawner')
  const [newTemplateId, setNewTemplateId] = useState('')
  const [query, setQuery] = useState('')
  const [filterKind, setFilterKind] = useState('')
  const [filterEnabled, setFilterEnabled] = useState('')
  const [filterTemplate, setFilterTemplate] = useState('')
  const [filterRegion, setFilterRegion] = useState('')
  const [minimumLevel, setMinimumLevel] = useState('')
  const [maximumLevel, setMaximumLevel] = useState('')
  const [selectedWorldName, setSelectedWorldName] = useState('')
  const [bulkSelection, setBulkSelection] = useState<{ worldId: string; ids: ReadonlySet<string> }>({ worldId: snapshot.manifest.worldId, ids: new Set() })
  const [selectedOnly, setSelectedOnly] = useState(false)
  const [visibleCount, setVisibleCount] = useState(50)
  const selectPlacement = (id: string | null, location?: WorldPlacement) => onSelected(id, location ?? working.find(item => item.placementId === id))
  const selected = working.find(item => item.placementId === selectedId && item.worldId === snapshot.manifest.worldId)
  const editNumber = (id: string, field: PlacementNumericField, raw: string) => {
    if (busyRef.current) return
    setDrafts(current => ({ ...current, [id]: { ...current[id], [field]: raw } }))
  }
  const discardNumber = (id: string, field: PlacementNumericField) => {
    if (busyRef.current) return
    setDrafts(current => {
      const next = { ...current }, fields = { ...next[id] }
      delete fields[field]
      if (Object.keys(fields).length) next[id] = fields
      else delete next[id]
      return next
    })
  }
  const commitNumber = (id: string, field: PlacementNumericField) => {
    if (busyRef.current || drafts[id]?.[field] === undefined || parsePlacementNumber(drafts[id][field]!, field) === null) return
    setWorking(applyPlacementDrafts(working, { [id]: { [field]: drafts[id][field] } }))
    discardNumber(id, field)
  }
  const travelHistory = (direction: 'past' | 'future') => {
    if (busyRef.current || hasDrafts) return
    setHistory(current => {
      const source = current[direction]
      if (!source.length) return current
      const target = source[source.length - 1]
      return direction === 'past'
        ? { past: source.slice(0, -1), working: target, future: [...current.future, current.working] }
        : { past: [...current.past, current.working], working: target, future: source.slice(0, -1) }
    })
    onPlacing(false); onMoving(false); setSelectedOnly(false); setBulkSelection({ worldId: snapshot.manifest.worldId, ids: new Set() }); setNotice('')
  }
  useEffect(() => {
    if (editing && selectedId && inspector.current) inspector.current.scrollTop = 0
  }, [selectedId, editing, selectionKey])
  const worldId = snapshot.manifest.worldId
  const bulkIds = bulkSelection.worldId === worldId ? bulkSelection.ids : emptySelection
  useEffect(() => { setBulkSelection({ worldId, ids: new Set() }); setSelectedOnly(false) }, [worldId])
  const masterNumbers = useMemo(() => spawnerMasterNumbers(catalog.spawners.map(item => item.id)), [catalog.spawners])
  const worldNames = useMemo(() => {
    const existing = [...new Set(base?.placements.filter(item => item.worldId === worldId).map(item => item.worldName) ?? [])]
    if (existing.length) return existing
    const fallback = catalog.worlds.find(item => item.id === worldId)?.baseWorldPath
    return fallback ? [fallback] : []
  }, [base, catalog.worlds, worldId])
  useEffect(() => { setSelectedWorldName(current => worldNames.includes(current) ? current : worldNames[0] ?? '') }, [worldNames])
  useEffect(() => { onDirtyChange(dirty) }, [dirty])
  useEffect(() => { onBusyChange(busy) }, [busy])
  const grid = useMemo(() => new Map(snapshot.cells.map((cell, index) => [`${cell.x},${cell.z}`, index])), [snapshot])
  const regionNames = useMemo(() => new Map(snapshot.regions.map(region => [region.id, region.name])), [snapshot.regions])
  const spawners = useMemo(() => new Map(catalog.spawners.map(item => [item.id, item])), [catalog.spawners])
  const npcs = useMemo(() => new Map(catalog.npcs.map(item => [item.id, item])), [catalog.npcs])
  const regionName = (placement: WorldPlacement) => {
    const index = cellAtWorld(snapshot.manifest.bounds, grid, placement.x, placement.z)
    const regionId = index === undefined ? '' : snapshot.cells[index].regionId
    return regionNames.get(regionId) ?? '解析範囲外'
  }
  const display = useMemo(() => new Map(working.map(item => {
    const master = item.kind === 'spawner' ? spawners.get(item.templateId ?? '') : npcs.get(item.templateId ?? '')
    const name = item.kind === 'waystone' ? item.waystone?.name || item.waystone?.id || '道標' : master?.name ?? item.templateId ?? 'マスター未選択'
    const region = regionName(item)
    return [item.placementId, { name, region, levels: item.kind === 'spawner' ? spawners.get(item.templateId ?? '')?.levels ?? [] : [],
      search: `${item.placementId} ${item.templateId ?? ''} ${name} ${item.waystone?.id ?? ''} ${region} ${item.x} ${item.y} ${item.z}`.toLocaleLowerCase('ja-JP') }]
  })), [working, spawners, npcs, snapshot, grid, regionNames])
  const filtered = useMemo(() => working.filter(item => item.worldId === worldId).filter(item => {
    const info = display.get(item.placementId)!
    if (filterKind && item.kind !== filterKind) return false
    if (filterEnabled && String(item.enabled) !== filterEnabled) return false
    if (filterTemplate && item.templateId !== filterTemplate) return false
    if (filterRegion && info.region !== filterRegion) return false
    if (minimumLevel || maximumLevel) {
      const levels = info.levels
      if (!levels.some(level => (!minimumLevel || level >= Number(minimumLevel)) && (!maximumLevel || level <= Number(maximumLevel)))) return false
    }
    return info.search.includes(query.trim().toLocaleLowerCase('ja-JP'))
  }), [working, worldId, filterKind, filterEnabled, filterTemplate, filterRegion, minimumLevel, maximumLevel, query, display])
  const filteredIds = useMemo(() => new Set(filtered.map(item => item.placementId)), [filtered])
  const bulkScope = useMemo(() => placementSelectionScope(working, bulkIds, worldId, filteredIds), [working, bulkIds, worldId, filteredIds])
  const listed = selectedOnly ? bulkScope.items : filtered
  const bulkLocked = busy || hasDrafts || placing || moving || !base
  const selectBulk = (ids: ReadonlySet<string>) => {
    if (busyRef.current || hasDrafts || placing || moving || !editing || !base) return
    setBulkSelection({ worldId, ids })
  }
  useEffect(() => { setVisibleCount(50) }, [filterKind, filterEnabled, filterTemplate, filterRegion, minimumLevel, maximumLevel, query, worldId, selectedOnly])
  const selectedOutsideFilter = editing && selected?.worldId === worldId && !filtered.some(item => item.placementId === selectedId)
  const mapPlacements = useMemo(() => editing
    ? selectedOutsideFilter && selected ? [...filtered, selected] : filtered
    : working.filter(item => item.worldId === worldId), [editing, filtered, selectedOutsideFilter, selected, working, worldId])
  useEffect(() => { onPlacements(mapPlacements, catalog, base !== null) }, [mapPlacements, catalog, base])
  useEffect(() => {
    let cancelled = false
    Promise.all([
      worldPlacementApi.read(),
      readCatalog(),
    ]).then(([value, definitions]) => {
      if (cancelled) return
      setBase(value); resetWorking(value.placements); setCatalog(definitions)
    }).catch(reason => { if (!cancelled) setError(reason instanceof Error ? reason.message : String(reason)) })
    return () => { cancelled = true }
  }, []) // Initial load only; reload while dirty requires an explicit choice.
  useEffect(() => { if (dirty) {
    const listener = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = '' }
    window.addEventListener('beforeunload', listener)
    return () => window.removeEventListener('beforeunload', listener)
  } }, [dirty])
  useEffect(() => {
    if (!point || busyRef.current || hasDrafts || !(placing || moving)) return
    if (point.y === null) { setError('この地点の立ち位置は解析されていません。座標を入力して指定してください。'); return }
    if (moving && selected) {
      update({ x: point.x, y: point.y, z: point.z }); onMoving(false)
      setNotice(`地図の安全な立ち位置 X ${point.x} / Y ${point.y} / Z ${point.z} へ移動しました。Dev に保存してください。`)
      return
    }
    if (!placing || !selectedWorldName) { setError('追加先のワールド名を選んでください。'); return }
    if (kind !== 'waystone' && !newTemplateId) { setError('追加するマスターを先に選んでください。'); return }
    const item = fresh(kind, worldId, selectedWorldName, kind === 'waystone' ? null : newTemplateId, point.x, point.y, point.z)
    setWorking([...working, item])
    setError(''); setNotice(`地図の安全な立ち位置 X ${point.x} / Y ${point.y} / Z ${point.z} に追加しました。内容を確認して Dev に保存してください。`)
    selectPlacement(item.placementId, item); onPlacing(false)
  }, [point?.key])
  const update = (patch: Partial<WorldPlacement>) => {
    if (busyRef.current || !selected) return
    if (patch.waystone && base?.placements.some(item => item.placementId === selected.placementId)
      && patch.waystone.id !== selected.waystone?.id) return
    const next = working.map(item => item.placementId === selected.placementId ? { ...item, ...patch } : item)
    setWorking(next); setNotice('')
  }
  const add = () => {
    if (busyRef.current || hasDrafts || !base || !selectedWorldName) { setError('追加先のワールド名を選んでください。'); return }
    const spawn = snapshot.manifest.spawn
    const item = fresh(kind, worldId, selectedWorldName, kind === 'waystone' ? null : newTemplateId || null, Math.floor(spawn.x), Math.floor(spawn.y), Math.floor(spawn.z))
    const next = [...working, item]; setWorking(next); selectPlacement(item.placementId, item)
    setNotice('開始地点の座標で追加しました。座標とマスターを確認してください。')
  }
  const duplicate = () => {
    if (busyRef.current || !selected || hasDrafts) return
    const copy = { ...selected, placementId: crypto.randomUUID(), x: selected.x + 1, waystone: selected.waystone ? { ...selected.waystone, id: '', name: `${selected.waystone.name} の複製` } : null }
    const next = [...working, copy]; setWorking(next); selectPlacement(copy.placementId, copy)
  }
  const bulkAction = (action: 'delete' | 'enable' | 'disable', requestedIds: ReadonlySet<string> = bulkIds) => {
    if (busyRef.current || hasDrafts || placing || moving || !editing || !base) return
    const scope = placementSelectionScope(working, requestedIds, worldId, filteredIds)
    if (!scope.items.length) return
    const verb = action === 'delete' ? '削除対象に' : action === 'enable' ? '有効に' : '無効に'
    if ((action === 'delete' || scope.outsideCount > 0) && !window.confirm(`合計 ${scope.items.length} 件（現在の表示条件内 ${scope.matchingCount} 件・表示条件外 ${scope.outsideCount} 件）を${verb}します。表示条件外も含む全選択が対象です。Dev への反映には保存が必要です。続けますか？`)) return
    const next = action === 'delete' ? removePlacements(working, scope.ids, worldId) : setPlacementsEnabled(working, scope.ids, worldId, action === 'enable')
    setWorking(next)
    setNotice(next === working ? '選択した配置はすべて指定の有効状態です。変更はありません。' : `${scope.items.length} 件を${verb}しました。Dev に保存する前に変更内容を確認してください。`)
    if (action === 'delete') { setSelectedOnly(false); if (selectedId && scope.ids.has(selectedId)) selectPlacement(null); setBulkSelection({ worldId, ids: new Set() }) }
  }
  const reload = async () => {
    if (busyRef.current) return
    if (dirty && !window.confirm('未保存の編集を破棄して Dev 配置を再読込しますか？')) return
    busyRef.current = true; setBusy(true); setError('')
    try {
      // A base snapshot is usable only after both placements and definitions load.
      // Retry both after an initial catalog failure; do not publish a partial pair.
      const [value, definitions] = await Promise.all([worldPlacementApi.read(), readCatalog()])
      setBase(value); resetWorking(value.placements); setCatalog(definitions); selectPlacement(null); setNotice('Dev 配置を再読込しました。')
    }
    catch (reason) { setError(reason instanceof Error ? reason.message : String(reason)) }
    finally { busyRef.current = false; setBusy(false) }
  }
  const save = async () => {
    if (busyRef.current || !base || !dirty) return
    if (invalidDraftId) { setError('入力途中の数値があります。該当する配置で数値を修正してください。'); selectPlacement(invalidDraftId); return }
    const pending = applyPlacementDrafts(working, drafts)
    const invalid = pending.find(item => !item.worldId || ![item.x, item.y, item.z, item.yaw, item.pitch].every(Number.isFinite)
      || (item.kind !== 'waystone' && !item.templateId)
      || (item.kind === 'waystone' && (!item.waystone?.id.trim() || !item.waystone.name.trim())))
    if (invalid) { setError(`保存できない配置があります（${invalid.placementId}）。マスター、道標 ID・名前、座標を確認してください。`); selectPlacement(invalid.placementId); return }
    if (hasDrafts) setWorking(pending)
    busyRef.current = true; setBusy(true); setError('')
    try { const value = await worldPlacementApi.save(base.revision, pending); setBase(value); resetWorking(value.placements); setNotice('Dev 配置を保存しました。ゲーム内への反映には /worldplacements reload が必要です。共有チャンネルには反映されていません。') }
    catch (reason) { setError(reason instanceof Error ? reason.message : String(reason)) }
    finally { busyRef.current = false; setBusy(false) }
  }
  const options = selected?.kind === 'spawner' ? catalog.spawners : catalog.npcs
  return <section className={`wp-editor ${editing ? 'wp-editing' : 'wp-planning'}`} aria-label="Dev ワールド配置エディタ" onKeyDown={event => {
    if (!editing || !(event.ctrlKey || event.metaKey) || event.altKey || (event.target instanceof HTMLElement && (event.target.closest('input, textarea, select') || event.target.isContentEditable))) return
    const key = event.key.toLowerCase()
    if (key !== 'z' && key !== 'y') return
    event.preventDefault()
    travelHistory(key === 'y' || event.shiftKey ? 'future' : 'past')
  }}>
    {editing && <><header><div><h2>保存済み Dev 配置を編集</h2><p>地図上の点を選択して編集します。保存後、ゲーム内には /worldplacements reload が必要です。</p></div><strong>{working.filter(item => item.worldId === worldId).length} 件{dirty ? ' · 未保存' : ''}</strong></header>
    {error && <p className="wp-error" role="alert">{error}</p>}{notice && <p role="status">{notice}</p>}
    <div className="wp-edit-summary" aria-live="polite"><span>未保存の変更: 追加 {changes.added} 件 · 変更 {changes.changed} 件 · 削除 {changes.deleted} 件</span><button className="button" onClick={() => travelHistory('past')} disabled={busy || hasDrafts || !history.past.length}>元に戻す</button><button className="button" onClick={() => travelHistory('future')} disabled={busy || hasDrafts || !history.future.length}>やり直す</button></div>
    {hasDrafts && <p className="wp-draft-note" role="status">{invalidDraftId ? '入力途中または不正な数値があります。修正するまで保存できません。Escapeでその欄の入力を取り消せます。' : '数値をEnterまたは入力欄からの移動で確定してください。保存時も有効な値を確定します。'}{Object.keys(drafts).map(id => <button className="button" key={id} onClick={() => selectPlacement(id)}>数値の入力中: {display.get(id)?.name ?? id}</button>)}</p>}
    <div className="wp-actions wp-toolbar"><label>追加先のワールド<select value={selectedWorldName} onChange={event => setSelectedWorldName(event.target.value)} disabled={busy || !worldNames.length}><option value="">選択してください</option>{worldNames.map(name => <option key={name} value={name}>{name}</option>)}</select></label><label>追加する種類<select value={kind} onChange={event => { setKind(event.target.value as PlacementKind); setNewTemplateId(''); onPlacing(false) }} disabled={busy}><option value="spawner">スポナー</option><option value="npc">NPC</option><option value="waystone">道標</option></select></label>{kind !== 'waystone' && <label>追加する{kind === 'spawner' ? 'スポナー' : 'NPC'}マスター<select aria-label="追加するマスター" value={newTemplateId} onChange={event => setNewTemplateId(event.target.value)} disabled={busy}><option value="">選択してください</option>{(kind === 'spawner' ? catalog.spawners : catalog.npcs).map(item => <option key={item.id} value={item.id}>{clean(item.name)} ({item.id})</option>)}</select></label>}<button className="button" aria-pressed={placing} onClick={() => { onPlacing(!placing); onMoving(false); setError('') }} disabled={busy || hasDrafts || !base || !selectedWorldName || (kind !== 'waystone' && !newTemplateId)}>地図で追加</button><button className="button" onClick={add} disabled={busy || hasDrafts || !base || !selectedWorldName}>座標を入力して追加</button><button className="button" aria-pressed={moving} onClick={() => { onMoving(!moving); onPlacing(false); setError('') }} disabled={busy || !selected || hasDrafts}>地図で移動</button><button className="button" onClick={() => { onPlacing(false); onMoving(false) }} disabled={!placing && !moving}>操作をキャンセル</button><button className="button" onClick={() => void save()} disabled={busy || !dirty || !base || !!invalidDraftId}>Dev に保存</button><button className="button" onClick={() => void reload()} disabled={busy}>Dev を再読込</button></div>
    {(placing || moving) && <p className="wp-instruction" role="status">{placing ? '地図の安全な地点をクリックして追加します。' : '移動先の安全な地点をクリックします。'} ドラッグで地図を移動できます。</p>}</>}
    {editing && <PlacementChangeReview changes={changeEntries} catalog={catalog} locked={busy || placing || moving} hasDrafts={hasDrafts} onLocate={item => {
      if (!busyRef.current && !placing && !moving) selectPlacement(item.placementId, item)
    }} onRevert={change => {
      if (busyRef.current || placing || moving || hasDrafts) return
      setWorking(revertPlacementChange(working, change)); setNotice('この配置の変更を取り消しました。元に戻す操作で復元できます。')
      setBulkSelection(current => ({ ...current, ids: new Set([...current.ids].filter(id => id !== change.id)) }))
      if (!change.before && selectedId === change.id) selectPlacement(null)
    }} />}
    <div className="wp-map-pane">{map}</div>
    {editing && <>
    <div className="wp-inspector" ref={inspector}>
    {selected && <div className="wp-form"><h3>{selected.kind === 'spawner' ? 'スポナー' : selected.kind === 'npc' ? 'NPC' : '道標'}の配置を編集</h3>{selectedOutsideFilter && <p className="wp-filter-note">選択中の配置は絞り込み条件外です。地図にはこの1件を表示しています。</p>}<code>{selected.placementId}</code>
      {selected.kind !== 'waystone' && <label>マスター<select value={selected.templateId ?? ''} disabled={busy} onChange={event => update({ templateId: event.target.value || null })}><option value="">選択してください</option>{options.map(item => <option key={item.id} value={item.id}>{clean(item.name)} ({item.id})</option>)}</select></label>}
      <div className="wp-coordinates">{(['x', 'y', 'z', 'yaw', 'pitch'] as const).map(key => <label key={key}>{key.toUpperCase()}<input disabled={busy} type="text" inputMode="decimal" aria-invalid={drafts[selected.placementId]?.[key] !== undefined && parsePlacementNumber(drafts[selected.placementId][key]!, key) === null} value={drafts[selected.placementId]?.[key] ?? String(selected[key])} onChange={event => editNumber(selected.placementId, key, event.target.value)} onBlur={() => commitNumber(selected.placementId, key)} onKeyDown={event => { if (event.key === 'Enter') { event.preventDefault(); commitNumber(selected.placementId, key) } else if (event.key === 'Escape') { event.preventDefault(); discardNumber(selected.placementId, key) } }} /></label>)}</div>
      <label><input type="checkbox" disabled={busy} checked={selected.enabled} onChange={event => update({ enabled: event.target.checked })} />有効</label>
      {selected.kind === 'spawner' && <p>出現範囲: {catalog.spawners.find(item => item.id === selected.templateId)?.radiusMeters ?? 'マスター未選択'} m</p>}
      {selected.kind === 'waystone' && selected.waystone && <div className="wp-waystone"><label>道標 ID<input value={selected.waystone.id} disabled={busy || !!base?.placements.some(item => item.placementId === selected.placementId)} onChange={event => update({ waystone: { ...selected.waystone!, id: event.target.value } })} /></label><label>表示名<input value={selected.waystone.name} disabled={busy} onChange={event => update({ waystone: { ...selected.waystone!, name: event.target.value } })} /></label><label>アイコン<input value={selected.waystone.icon} disabled={busy} onChange={event => update({ waystone: { ...selected.waystone!, icon: event.target.value } })} /></label><label>解放費用<input type="text" inputMode="decimal" value={drafts[selected.placementId]?.unlockGold ?? String(selected.waystone.unlockGold)} aria-invalid={drafts[selected.placementId]?.unlockGold !== undefined && parsePlacementNumber(drafts[selected.placementId].unlockGold!, 'unlockGold') === null} disabled={busy} onChange={event => editNumber(selected.placementId, 'unlockGold', event.target.value)} onBlur={() => commitNumber(selected.placementId, 'unlockGold')} onKeyDown={event => { if (event.key === 'Enter') { event.preventDefault(); commitNumber(selected.placementId, 'unlockGold') } else if (event.key === 'Escape') { event.preventDefault(); discardNumber(selected.placementId, 'unlockGold') } }} /></label><label><input type="checkbox" checked={selected.waystone.lockEnabled} disabled={busy} onChange={event => update({ waystone: { ...selected.waystone!, lockEnabled: event.target.checked } })} />解放条件を使用</label></div>}
      <div className="wp-actions"><button className="button" onClick={duplicate} disabled={busy || hasDrafts}>複製</button><button className="button" onClick={() => bulkAction('delete', new Set([selected.placementId]))} disabled={bulkLocked}>この配置を削除</button></div>
    </div>}
    <div className="wp-filters"><label>検索<input aria-label="保存済み配置を検索" value={query} onChange={event => setQuery(event.target.value)} placeholder="モブ名・ID・座標" /></label><label>種類<select value={filterKind} onChange={event => setFilterKind(event.target.value)}><option value="">すべて</option><option value="spawner">スポナー</option><option value="npc">NPC</option><option value="waystone">道標</option></select></label><label>有効状態<select value={filterEnabled} onChange={event => setFilterEnabled(event.target.value)}><option value="">すべて</option><option value="true">有効</option><option value="false">無効</option></select></label><label>地域<select value={filterRegion} onChange={event => setFilterRegion(event.target.value)}><option value="">すべて</option>{[...new Set(snapshot.regions.map(region => region.name))].map(name => <option key={name} value={name}>{name}</option>)}</select></label><label>スポナーマスター<select value={filterTemplate} onChange={event => setFilterTemplate(event.target.value)}><option value="">すべて</option>{catalog.spawners.map(item => <option key={item.id} value={item.id}>{clean(item.name)} ({item.id})</option>)}</select></label><label>出現レベル下限<input aria-label="出現レベル下限" type="number" min="1" value={minimumLevel} onChange={event => setMinimumLevel(event.target.value)} /></label><label>出現レベル上限<input aria-label="出現レベル上限" type="number" min="1" value={maximumLevel} onChange={event => setMaximumLevel(event.target.value)} /></label></div>
    <p className="wp-count">{filtered.length} 件を表示 · 世界: {snapshot.manifest.worldName} ({worldId}){selectedWorldName && ` · 保存先: ${selectedWorldName}`}</p>
    <div className="wp-bulk-panel" aria-label="配置の一括操作">
      <p className="wp-bulk-scope" role="status">一括選択: 合計 {bulkScope.items.length} 件 · 現在の表示条件内 {bulkScope.matchingCount} 件 · 表示条件外 {bulkScope.outsideCount} 件</p>
      <div className="wp-actions"><button className="button" disabled={bulkLocked || !filtered.length} onClick={() => selectBulk(new Set([...bulkScope.ids, ...filteredIds]))}>現在の表示条件に合う全 {filtered.length} 件を選択</button><button className="button" disabled={bulkLocked || !bulkScope.items.length} onClick={() => selectBulk(new Set())}>一括選択をすべて解除</button></div>
      <label className="wp-selected-only"><input type="checkbox" checked={selectedOnly} onChange={event => setSelectedOnly(event.target.checked)} />一括選択だけを一覧で確認（表示条件外を含む）</label>
      <p>一括操作は表示条件外を含む全選択が対象です。条件外がある操作と削除は、実行前に件数を確認します。</p>
      <div className="wp-actions"><button className="button" disabled={bulkLocked || !bulkScope.items.length} onClick={() => bulkAction('enable')}>選択した {bulkScope.items.length} 件を有効にする</button><button className="button" disabled={bulkLocked || !bulkScope.items.length} onClick={() => bulkAction('disable')}>選択した {bulkScope.items.length} 件を無効にする</button><button className="button" disabled={bulkLocked || !bulkScope.items.length} onClick={() => bulkAction('delete')}>選択した {bulkScope.items.length} 件を削除</button></div>
    </div>
    <div className="wp-list" aria-label={selectedOnly ? '一括選択した配置の一覧' : '表示条件に合う配置の一覧'}>{listed.slice(0, visibleCount).map(item => <div key={item.placementId} className="wp-row"><input type="checkbox" disabled={bulkLocked} aria-label={`${display.get(item.placementId)!.name} X ${item.x} / Y ${item.y} / Z ${item.z} (${item.placementId}) を一括選択`} checked={bulkScope.ids.has(item.placementId)} onChange={event => { const ids = new Set(bulkScope.ids); if (event.target.checked) ids.add(item.placementId); else ids.delete(item.placementId); selectBulk(ids) }} /><button type="button" aria-pressed={selectedId === item.placementId} onClick={() => { if (inspector.current) inspector.current.scrollTop = 0; selectPlacement(item.placementId) }}><strong>{item.kind === 'spawner' && <span className="wp-map-number">#{masterNumbers.get(item.templateId ?? '') ?? '?'} </span>}<MinecraftText value={display.get(item.placementId)!.name} /></strong><small>{selectedOnly && !filteredIds.has(item.placementId) && '表示条件外 · '}{item.kind} · {display.get(item.placementId)!.region} · X {item.x} / Y {item.y} / Z {item.z} · {item.enabled ? '有効' : '無効'}</small><code>{item.templateId ?? item.placementId}</code></button></div>)}{!listed.length && <p>{selectedOnly ? '一括選択した配置はありません。' : '条件に合う保存済み配置はありません。'}</p>}</div>
    <div className="wp-actions"><span>{selectedOnly ? '一括選択の一覧' : '一覧'} {Math.min(visibleCount, listed.length)} / {listed.length} 件</span>{visibleCount < listed.length && <button className="button" onClick={() => setVisibleCount(count => count + 50)}>さらに50件表示</button>}</div>

    </div>
    </>}
  </section>
}
