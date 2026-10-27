import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { worldPlacementApi, type PlacementKind, type PlacementSnapshot, type WorldPlacement } from '../api/worldPlacementApi'
import { cellAtWorld, type PlanningSnapshot } from '../data/spawnerPlanning'
import { MinecraftText } from './MinecraftText'
import { spawnerMasterNumbers } from '../data/spawnerMapStyle'
import './worldPlacementEditor.css'

export interface PlacementCatalog { spawners: { id: string; name: string; radiusMeters: number; levels: number[] }[]; npcs: { id: string; name: string }[]; worlds: { id: string; name: string; baseWorldPath: string }[] }
export interface PlacementPoint { key: number; x: number; y: number | null; z: number }
interface Props {
  editing?: boolean
  map?: ReactNode
  snapshot: PlanningSnapshot
  point: PlacementPoint | null
  selectedId: string | null
  onSelected: (id: string | null) => void
  onPlacements: (placements: WorldPlacement[], catalog: PlacementCatalog) => void
  placing: boolean
  onPlacing: (placing: boolean) => void
  moving?: boolean
  onMoving?: (moving: boolean) => void
  onBusyChange?: (busy: boolean) => void
  onDirtyChange?: (dirty: boolean) => void
}
const emptyCatalog: PlacementCatalog = { spawners: [], npcs: [], worlds: [] }
const clean = (value: string) => value.replace(/[&§][0-9a-fk-or]/gi, '')
const templateName = (placement: WorldPlacement, catalog: PlacementCatalog) => placement.kind === 'waystone'
  ? placement.waystone?.name || placement.waystone?.id || '道標'
  : (placement.kind === 'spawner' ? catalog.spawners : catalog.npcs).find(item => item.id === placement.templateId)?.name ?? placement.templateId ?? 'マスター未選択'
const fresh = (kind: PlacementKind, worldId: string, worldName: string, templateId: string | null, x: number, y: number, z: number): WorldPlacement => ({
  placementId: crypto.randomUUID(), kind, worldId, worldName, templateId, x, y, z, yaw: 0, pitch: 0, enabled: true,
  waystone: kind === 'waystone' ? { id: '', name: '', lockEnabled: false, unlockGold: 0, icon: 'LODESTONE', createdAt: new Date().toISOString(), createdBy: 'master-data-editor' } : null,
})

export function WorldPlacementEditor({ editing = true, map, snapshot, point, selectedId, onSelected, onPlacements, placing, onPlacing, moving = false, onMoving = () => {}, onBusyChange = () => {}, onDirtyChange = () => {} }: Props) {
  const [base, setBase] = useState<PlacementSnapshot | null>(null)
  const [working, setWorking] = useState<WorldPlacement[]>([])
  const [catalog, setCatalog] = useState<PlacementCatalog>(emptyCatalog)
  const [dirty, setDirty] = useState(false)
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
  const [selectedForDelete, setSelectedForDelete] = useState<string[]>([])
  const selected = working.find(item => item.placementId === selectedId)
  useEffect(() => {
    if (editing && selectedId && inspector.current) inspector.current.scrollTop = 0
  }, [selectedId, editing])
  const worldId = snapshot.manifest.worldId
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
  const regionName = (placement: WorldPlacement) => {
    const index = cellAtWorld(snapshot.manifest.bounds, grid, placement.x, placement.z)
    const regionId = index === undefined ? '' : snapshot.cells[index].regionId
    return snapshot.regions.find(region => region.id === regionId)?.name ?? '解析範囲外'
  }
  const filtered = useMemo(() => working.filter(item => item.worldId === worldId).filter(item => {
    if (filterKind && item.kind !== filterKind) return false
    if (filterEnabled && String(item.enabled) !== filterEnabled) return false
    if (filterTemplate && item.templateId !== filterTemplate) return false
    if (filterRegion && regionName(item) !== filterRegion) return false
    if (minimumLevel || maximumLevel) {
      const levels = item.kind === 'spawner' ? catalog.spawners.find(master => master.id === item.templateId)?.levels ?? [] : []
      if (!levels.some(level => (!minimumLevel || level >= Number(minimumLevel)) && (!maximumLevel || level <= Number(maximumLevel)))) return false
    }
    const searchable = `${item.placementId} ${item.templateId ?? ''} ${templateName(item, catalog)} ${item.waystone?.id ?? ''} ${regionName(item)} ${item.x} ${item.y} ${item.z}`.toLocaleLowerCase('ja-JP')
    return searchable.includes(query.trim().toLocaleLowerCase('ja-JP'))
  }), [working, worldId, filterKind, filterEnabled, filterTemplate, filterRegion, minimumLevel, maximumLevel, query, catalog, snapshot, grid])
  const selectedOutsideFilter = editing && selected?.worldId === worldId && !filtered.some(item => item.placementId === selectedId)
  const mapPlacements = useMemo(() => editing
    ? selectedOutsideFilter && selected ? [...filtered, selected] : filtered
    : working.filter(item => item.worldId === worldId), [editing, filtered, selectedOutsideFilter, selected, working, worldId])
  useEffect(() => { onPlacements(mapPlacements, catalog) }, [mapPlacements, catalog])
  useEffect(() => {
    let cancelled = false
    Promise.all([
      worldPlacementApi.read(),
      fetch('/api/world-placements/catalog').then(async response => {
        const payload = await response.json()
        if (!response.ok) throw new Error(payload.title ?? '配置マスターを読み込めません。')
        return payload as PlacementCatalog
      }),
    ]).then(([value, definitions]) => {
      if (cancelled) return
      setBase(value); setWorking(value.placements); setCatalog(definitions)
    }).catch(reason => { if (!cancelled) setError(reason instanceof Error ? reason.message : String(reason)) })
    return () => { cancelled = true }
  }, []) // Initial load only; reload while dirty requires an explicit choice.
  useEffect(() => { if (dirty) {
    const listener = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = '' }
    window.addEventListener('beforeunload', listener)
    return () => window.removeEventListener('beforeunload', listener)
  } }, [dirty])
  useEffect(() => {
    if (!point || busyRef.current || !(placing || moving)) return
    if (point.y === null) { setError('この地点の立ち位置は解析されていません。座標を入力して指定してください。'); return }
    if (moving && selected) {
      update({ x: point.x, y: point.y, z: point.z }); onMoving(false)
      setNotice(`地図の安全な立ち位置 X ${point.x} / Y ${point.y} / Z ${point.z} へ移動しました。Dev に保存してください。`)
      return
    }
    if (!placing || !selectedWorldName) { setError('追加先のワールド名を選んでください。'); return }
    if (kind !== 'waystone' && !newTemplateId) { setError('追加するマスターを先に選んでください。'); return }
    const item = fresh(kind, worldId, selectedWorldName, kind === 'waystone' ? null : newTemplateId, point.x, point.y, point.z)
    setWorking(current => [...current, item])
    setDirty(true); setError(''); setNotice(`地図の安全な立ち位置 X ${point.x} / Y ${point.y} / Z ${point.z} に追加しました。内容を確認して Dev に保存してください。`)
    onSelected(item.placementId); onPlacing(false)
  }, [point?.key])
  const update = (patch: Partial<WorldPlacement>) => {
    if (busyRef.current || !selected) return
    if (patch.waystone && base?.placements.some(item => item.placementId === selected.placementId)
      && patch.waystone.id !== selected.waystone?.id) return
    const next = working.map(item => item.placementId === selected.placementId ? { ...item, ...patch } : item)
    setWorking(next); setDirty(true); setNotice('')
  }
  const add = () => {
    if (busyRef.current || !base || !selectedWorldName) { setError('追加先のワールド名を選んでください。'); return }
    const spawn = snapshot.manifest.spawn
    const item = fresh(kind, worldId, selectedWorldName, kind === 'waystone' ? null : newTemplateId || null, Math.floor(spawn.x), Math.floor(spawn.y), Math.floor(spawn.z))
    const next = [...working, item]; setWorking(next); onSelected(item.placementId); setDirty(true)
    setNotice('開始地点の座標で追加しました。座標とマスターを確認してください。')
  }
  const duplicate = () => {
    if (busyRef.current || !selected) return
    const copy = { ...selected, placementId: crypto.randomUUID(), x: selected.x + 1, waystone: selected.waystone ? { ...selected.waystone, id: '', name: `${selected.waystone.name} の複製` } : null }
    const next = [...working, copy]; setWorking(next); onSelected(copy.placementId); setDirty(true)
  }
  const remove = (ids: string[]) => {
    if (busyRef.current || !ids.length || !window.confirm(`${ids.length} 件の保存済み Dev 配置を削除対象にします。保存すると削除されます。続けますか？`)) return
    const next = working.filter(item => !ids.includes(item.placementId))
    setWorking(next); setDirty(true); onSelected(null); setSelectedForDelete([])
  }
  const reload = async () => {
    if (busyRef.current) return
    if (dirty && !window.confirm('未保存の編集を破棄して Dev 配置を再読込しますか？')) return
    busyRef.current = true; setBusy(true); setError('')
    try { const value = await worldPlacementApi.read(); setBase(value); setWorking(value.placements); setDirty(false); onSelected(null); setNotice('Dev 配置を再読込しました。') }
    catch (reason) { setError(reason instanceof Error ? reason.message : String(reason)) }
    finally { busyRef.current = false; setBusy(false) }
  }
  const save = async () => {
    if (busyRef.current || !base || !dirty) return
    const invalid = working.find(item => !item.worldId || ![item.x, item.y, item.z, item.yaw, item.pitch].every(Number.isFinite)
      || (item.kind !== 'waystone' && !item.templateId)
      || (item.kind === 'waystone' && (!item.waystone?.id.trim() || !item.waystone.name.trim())))
    if (invalid) { setError(`保存できない配置があります（${invalid.placementId}）。マスター、道標 ID・名前、座標を確認してください。`); onSelected(invalid.placementId); return }
    busyRef.current = true; setBusy(true); setError('')
    try { const value = await worldPlacementApi.save(base.revision, working); setBase(value); setWorking(value.placements); setDirty(false); setNotice('Dev 配置を保存しました。ゲーム内への反映には /worldplacements reload が必要です。共有チャンネルには反映されていません。') }
    catch (reason) { setError(reason instanceof Error ? reason.message : String(reason)) }
    finally { busyRef.current = false; setBusy(false) }
  }
  const options = selected?.kind === 'spawner' ? catalog.spawners : catalog.npcs
  return <section className={`wp-editor ${editing ? 'wp-editing' : 'wp-planning'}`} aria-label="Dev ワールド配置エディタ">
    {editing && <><header><div><h2>保存済み Dev 配置を編集</h2><p>地図上の点を選択して編集します。保存後、ゲーム内には /worldplacements reload が必要です。</p></div><strong>{working.filter(item => item.worldId === worldId).length} 件{dirty ? ' · 未保存' : ''}</strong></header>
    {error && <p className="wp-error" role="alert">{error}</p>}{notice && <p role="status">{notice}</p>}
    <div className="wp-actions wp-toolbar"><label>追加先のワールド<select value={selectedWorldName} onChange={event => setSelectedWorldName(event.target.value)} disabled={busy || !worldNames.length}><option value="">選択してください</option>{worldNames.map(name => <option key={name} value={name}>{name}</option>)}</select></label><label>追加する種類<select value={kind} onChange={event => { setKind(event.target.value as PlacementKind); setNewTemplateId(''); onPlacing(false) }} disabled={busy}><option value="spawner">スポナー</option><option value="npc">NPC</option><option value="waystone">道標</option></select></label>{kind !== 'waystone' && <label>追加する{kind === 'spawner' ? 'スポナー' : 'NPC'}マスター<select aria-label="追加するマスター" value={newTemplateId} onChange={event => setNewTemplateId(event.target.value)} disabled={busy}><option value="">選択してください</option>{(kind === 'spawner' ? catalog.spawners : catalog.npcs).map(item => <option key={item.id} value={item.id}>{clean(item.name)} ({item.id})</option>)}</select></label>}<button className="button" aria-pressed={placing} onClick={() => { onPlacing(!placing); onMoving(false); setError('') }} disabled={busy || !base || !selectedWorldName || (kind !== 'waystone' && !newTemplateId)}>地図で追加</button><button className="button" onClick={add} disabled={busy || !base || !selectedWorldName}>座標を入力して追加</button><button className="button" aria-pressed={moving} onClick={() => { onMoving(!moving); onPlacing(false); setError('') }} disabled={busy || !selected}>地図で移動</button><button className="button" onClick={() => { onPlacing(false); onMoving(false) }} disabled={!placing && !moving}>操作をキャンセル</button><button className="button" onClick={() => void save()} disabled={busy || !dirty || !base}>Dev に保存</button><button className="button" onClick={() => void reload()} disabled={busy}>Dev を再読込</button></div>
    {(placing || moving) && <p className="wp-instruction" role="status">{placing ? '地図の安全な地点をクリックして追加します。' : '移動先の安全な地点をクリックします。'} ドラッグで地図を移動できます。</p>}</>}
    <div className="wp-map-pane">{map}</div>
    {editing && <>
    <div className="wp-inspector" ref={inspector}>
    {selected && <div className="wp-form"><h3>{selected.kind === 'spawner' ? 'スポナー' : selected.kind === 'npc' ? 'NPC' : '道標'}の配置を編集</h3>{selectedOutsideFilter && <p className="wp-filter-note">選択中の配置は絞り込み条件外です。地図にはこの1件を表示しています。</p>}<code>{selected.placementId}</code>
      {selected.kind !== 'waystone' && <label>マスター<select value={selected.templateId ?? ''} disabled={busy} onChange={event => update({ templateId: event.target.value || null })}><option value="">選択してください</option>{options.map(item => <option key={item.id} value={item.id}>{clean(item.name)} ({item.id})</option>)}</select></label>}
      <div className="wp-coordinates">{(['x', 'y', 'z', 'yaw', 'pitch'] as const).map(key => <label key={key}>{key.toUpperCase()}<input type="number" disabled={busy} step={key === 'yaw' || key === 'pitch' ? '0.1' : '1'} value={selected[key]} onChange={event => update({ [key]: Number(event.target.value) })} /></label>)}</div>
      <label><input type="checkbox" disabled={busy} checked={selected.enabled} onChange={event => update({ enabled: event.target.checked })} />有効</label>
      {selected.kind === 'spawner' && <p>出現範囲: {catalog.spawners.find(item => item.id === selected.templateId)?.radiusMeters ?? 'マスター未選択'} m</p>}
      {selected.kind === 'waystone' && selected.waystone && <div className="wp-waystone"><label>道標 ID<input value={selected.waystone.id} disabled={busy || !!base?.placements.some(item => item.placementId === selected.placementId)} onChange={event => update({ waystone: { ...selected.waystone!, id: event.target.value } })} /></label><label>表示名<input value={selected.waystone.name} disabled={busy} onChange={event => update({ waystone: { ...selected.waystone!, name: event.target.value } })} /></label><label>アイコン<input value={selected.waystone.icon} disabled={busy} onChange={event => update({ waystone: { ...selected.waystone!, icon: event.target.value } })} /></label><label>解放費用<input type="number" min="0" value={selected.waystone.unlockGold} disabled={busy} onChange={event => update({ waystone: { ...selected.waystone!, unlockGold: Number(event.target.value) } })} /></label><label><input type="checkbox" checked={selected.waystone.lockEnabled} disabled={busy} onChange={event => update({ waystone: { ...selected.waystone!, lockEnabled: event.target.checked } })} />解放条件を使用</label></div>}
      <div className="wp-actions"><button className="button" onClick={duplicate} disabled={busy}>複製</button><button className="button" onClick={() => remove([selected.placementId])} disabled={busy}>この配置を削除</button></div>
    </div>}
    <div className="wp-filters"><label>検索<input aria-label="保存済み配置を検索" value={query} onChange={event => setQuery(event.target.value)} placeholder="モブ名・ID・座標" /></label><label>種類<select value={filterKind} onChange={event => setFilterKind(event.target.value)}><option value="">すべて</option><option value="spawner">スポナー</option><option value="npc">NPC</option><option value="waystone">道標</option></select></label><label>有効状態<select value={filterEnabled} onChange={event => setFilterEnabled(event.target.value)}><option value="">すべて</option><option value="true">有効</option><option value="false">無効</option></select></label><label>地域<select value={filterRegion} onChange={event => setFilterRegion(event.target.value)}><option value="">すべて</option>{[...new Set(snapshot.regions.map(region => region.name))].map(name => <option key={name} value={name}>{name}</option>)}</select></label><label>スポナーマスター<select value={filterTemplate} onChange={event => setFilterTemplate(event.target.value)}><option value="">すべて</option>{catalog.spawners.map(item => <option key={item.id} value={item.id}>{clean(item.name)} ({item.id})</option>)}</select></label><label>出現レベル下限<input aria-label="出現レベル下限" type="number" min="1" value={minimumLevel} onChange={event => setMinimumLevel(event.target.value)} /></label><label>出現レベル上限<input aria-label="出現レベル上限" type="number" min="1" value={maximumLevel} onChange={event => setMaximumLevel(event.target.value)} /></label></div>
    <p className="wp-count">{filtered.length} 件を表示 · 世界: {snapshot.manifest.worldName} ({worldId}){selectedWorldName && ` · 保存先: ${selectedWorldName}`}</p>
    <div className="wp-list">{filtered.map(item => <div key={item.placementId} className="wp-row"><input type="checkbox" disabled={busy} aria-label={`${templateName(item, catalog)} を削除対象に選択`} checked={selectedForDelete.includes(item.placementId)} onChange={event => setSelectedForDelete(current => event.target.checked ? [...current, item.placementId] : current.filter(id => id !== item.placementId))} /><button type="button" aria-pressed={selectedId === item.placementId} onClick={() => onSelected(item.placementId)}><strong>{item.kind === 'spawner' && <span className="wp-map-number">#{masterNumbers.get(item.templateId ?? '') ?? '?'} </span>}<MinecraftText value={templateName(item, catalog)} /></strong><small>{item.kind} · {regionName(item)} · X {item.x} / Y {item.y} / Z {item.z} · {item.enabled ? '有効' : '無効'}</small><code>{item.templateId ?? item.placementId}</code></button></div>)}{!filtered.length && <p>条件に合う保存済み配置はありません。</p>}</div>
    <div className="wp-actions wp-delete-actions"><button className="button" disabled={busy || !selectedForDelete.length} onClick={() => remove(selectedForDelete)}>選択した {selectedForDelete.length} 件を削除</button></div>
    </div>
    </>}
  </section>
}
