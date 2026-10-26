import { useEffect, useMemo, useState } from 'react'
import { worldPlacementApi, type PlacementKind, type PlacementSnapshot, type WorldPlacement } from '../api/worldPlacementApi'
import { cellAtWorld, type PlanningSnapshot } from '../data/spawnerPlanning'
import { MinecraftText } from './MinecraftText'
import './worldPlacementEditor.css'

export interface PlacementCatalog { spawners: { id: string; name: string; radiusMeters: number }[]; npcs: { id: string; name: string }[] }
export interface PlacementPoint { key: number; x: number; y: number | null; z: number }
interface Props {
  snapshot: PlanningSnapshot
  point: PlacementPoint | null
  selectedId: string | null
  onSelected: (id: string | null) => void
  onPlacements: (placements: WorldPlacement[], catalog: PlacementCatalog) => void
  placing: boolean
  onPlacing: (placing: boolean) => void
}
const emptyCatalog: PlacementCatalog = { spawners: [], npcs: [] }
const clean = (value: string) => value.replace(/[&§][0-9a-fk-or]/gi, '')
const templateName = (placement: WorldPlacement, catalog: PlacementCatalog) => placement.kind === 'waystone'
  ? placement.waystone?.name || placement.waystone?.id || '道標'
  : (placement.kind === 'spawner' ? catalog.spawners : catalog.npcs).find(item => item.id === placement.templateId)?.name ?? placement.templateId ?? 'マスター未選択'
const fresh = (kind: PlacementKind, worldId: string, worldName: string, x: number, y: number, z: number): WorldPlacement => ({
  placementId: crypto.randomUUID(), kind, worldId, worldName, templateId: null, x, y, z, yaw: 0, pitch: 0, enabled: true,
  waystone: kind === 'waystone' ? { id: '', name: '', lockEnabled: false, unlockGold: 0, icon: 'LODESTONE', createdAt: new Date().toISOString(), createdBy: 'master-data-editor' } : null,
})

export function WorldPlacementEditor({ snapshot, point, selectedId, onSelected, onPlacements, placing, onPlacing }: Props) {
  const [base, setBase] = useState<PlacementSnapshot | null>(null)
  const [working, setWorking] = useState<WorldPlacement[]>([])
  const [catalog, setCatalog] = useState<PlacementCatalog>(emptyCatalog)
  const [dirty, setDirty] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [kind, setKind] = useState<PlacementKind>('spawner')
  const [query, setQuery] = useState('')
  const [filterKind, setFilterKind] = useState('')
  const [filterEnabled, setFilterEnabled] = useState('')
  const [filterTemplate, setFilterTemplate] = useState('')
  const [filterRegion, setFilterRegion] = useState('')
  const [selectedForDelete, setSelectedForDelete] = useState<string[]>([])
  const selected = working.find(item => item.placementId === selectedId)
  const worldId = snapshot.manifest.worldId
  const worldName = snapshot.manifest.worldName
  const grid = useMemo(() => new Map(snapshot.cells.map((cell, index) => [`${cell.x},${cell.z}`, index])), [snapshot])
  const regionName = (placement: WorldPlacement) => {
    const index = cellAtWorld(snapshot.manifest.bounds, grid, placement.x, placement.z)
    const regionId = index === undefined ? '' : snapshot.cells[index].regionId
    return snapshot.regions.find(region => region.id === regionId)?.name ?? '解析範囲外'
  }
  const filtered = working.filter(item => item.worldId === worldId).filter(item => {
    if (filterKind && item.kind !== filterKind) return false
    if (filterEnabled && String(item.enabled) !== filterEnabled) return false
    if (filterTemplate && item.templateId !== filterTemplate) return false
    if (filterRegion && regionName(item) !== filterRegion) return false
    const searchable = `${item.placementId} ${item.templateId ?? ''} ${templateName(item, catalog)} ${item.waystone?.id ?? ''} ${regionName(item)} ${item.x} ${item.y} ${item.z}`.toLocaleLowerCase('ja-JP')
    return searchable.includes(query.trim().toLocaleLowerCase('ja-JP'))
  })
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
      setBase(value); setWorking(value.placements); setCatalog(definitions); onPlacements(value.placements, definitions)
    }).catch(reason => { if (!cancelled) setError(reason instanceof Error ? reason.message : String(reason)) })
    return () => { cancelled = true }
  }, []) // Initial load only; reload while dirty requires an explicit choice.
  useEffect(() => { if (dirty) {
    const listener = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = '' }
    window.addEventListener('beforeunload', listener)
    return () => window.removeEventListener('beforeunload', listener)
  } }, [dirty])
  useEffect(() => {
    if (!point || !placing) return
    if (point.y === null) { setError('この地点は立ち位置の Y が解析されていません。座標入力から追加してください。'); return }
    const item = fresh(kind, worldId, worldName, point.x, point.y, point.z)
    setWorking(current => { const next = [...current, item]; onPlacements(next, catalog); return next })
    setDirty(true); setError(''); setNotice('地図から追加しました。マスターを選んで Dev に保存してください。')
    onSelected(item.placementId); onPlacing(false)
  }, [point?.key])
  const update = (patch: Partial<WorldPlacement>) => {
    if (!selected) return
    const next = working.map(item => item.placementId === selected.placementId ? { ...item, ...patch } : item)
    setWorking(next); onPlacements(next, catalog); setDirty(true); setNotice('')
  }
  const add = () => {
    const spawn = snapshot.manifest.spawn
    const item = fresh(kind, worldId, worldName, Math.floor(spawn.x), Math.floor(spawn.y), Math.floor(spawn.z))
    const next = [...working, item]; setWorking(next); onPlacements(next, catalog); onSelected(item.placementId); setDirty(true)
    setNotice('開始地点の座標で追加しました。座標とマスターを確認してください。')
  }
  const duplicate = () => {
    if (!selected) return
    const copy = { ...selected, placementId: crypto.randomUUID(), x: selected.x + 1, waystone: selected.waystone ? { ...selected.waystone, id: '', name: `${selected.waystone.name} の複製` } : null }
    const next = [...working, copy]; setWorking(next); onPlacements(next, catalog); onSelected(copy.placementId); setDirty(true)
  }
  const remove = (ids: string[]) => {
    if (!ids.length || !window.confirm(`${ids.length} 件の保存済み Dev 配置を削除対象にします。保存すると削除されます。続けますか？`)) return
    const next = working.filter(item => !ids.includes(item.placementId))
    setWorking(next); onPlacements(next, catalog); setDirty(true); onSelected(null); setSelectedForDelete([])
  }
  const reload = async () => {
    if (dirty && !window.confirm('未保存の編集を破棄して Dev 配置を再読込しますか？')) return
    setBusy(true); setError('')
    try { const value = await worldPlacementApi.read(); setBase(value); setWorking(value.placements); onPlacements(value.placements, catalog); setDirty(false); onSelected(null); setNotice('Dev 配置を再読込しました。') }
    catch (reason) { setError(reason instanceof Error ? reason.message : String(reason)) }
    finally { setBusy(false) }
  }
  const save = async () => {
    if (!base || !dirty) return
    const invalid = working.find(item => !item.worldId || ![item.x, item.y, item.z, item.yaw, item.pitch].every(Number.isFinite)
      || (item.kind !== 'waystone' && !item.templateId)
      || (item.kind === 'waystone' && (!item.waystone?.id.trim() || !item.waystone.name.trim())))
    if (invalid) { setError(`保存できない配置があります（${invalid.placementId}）。マスター、道標 ID・名前、座標を確認してください。`); onSelected(invalid.placementId); return }
    setBusy(true); setError('')
    try { const value = await worldPlacementApi.save(base.revision, working); setBase(value); setWorking(value.placements); onPlacements(value.placements, catalog); setDirty(false); setNotice('Dev 配置を保存しました。共有チャンネルには反映されていません。') }
    catch (reason) { setError(reason instanceof Error ? reason.message : String(reason)) }
    finally { setBusy(false) }
  }
  const options = selected?.kind === 'spawner' ? catalog.spawners : catalog.npcs
  return <section className="wp-editor" aria-label="Dev ワールド配置エディタ">
    <header><div><h2>保存済み Dev 配置</h2><p>解析案とは別の実配置データです。変更は Dev 配置 API にだけ保存します。</p></div><strong>{working.filter(item => item.worldId === worldId).length} 件{dirty ? ' · 未保存' : ''}</strong></header>
    {error && <p className="wp-error" role="alert">{error}</p>}{notice && <p role="status">{notice}</p>}
    <div className="wp-actions"><button className="button" onClick={() => void reload()} disabled={busy}>Dev を再読込</button><button className="button" onClick={() => void save()} disabled={busy || !dirty || !base}>Dev に保存</button><label>追加する種類<select value={kind} onChange={event => setKind(event.target.value as PlacementKind)}><option value="spawner">スポナー</option><option value="npc">NPC</option><option value="waystone">道標</option></select></label><button className="button" onClick={add} disabled={!base}>座標を入力して追加</button><button className="button" aria-pressed={placing} onClick={() => onPlacing(!placing)} disabled={!base}>地図をクリックして追加</button></div>
    <div className="wp-filters"><label>検索<input aria-label="保存済み配置を検索" value={query} onChange={event => setQuery(event.target.value)} placeholder="モブ名・ID・座標" /></label><label>種類<select value={filterKind} onChange={event => setFilterKind(event.target.value)}><option value="">すべて</option><option value="spawner">スポナー</option><option value="npc">NPC</option><option value="waystone">道標</option></select></label><label>有効状態<select value={filterEnabled} onChange={event => setFilterEnabled(event.target.value)}><option value="">すべて</option><option value="true">有効</option><option value="false">無効</option></select></label><label>地域<select value={filterRegion} onChange={event => setFilterRegion(event.target.value)}><option value="">すべて</option>{snapshot.regions.map(region => <option key={region.id} value={region.name}>{region.name}</option>)}</select></label><label>スポナーマスター<select value={filterTemplate} onChange={event => setFilterTemplate(event.target.value)}><option value="">すべて</option>{catalog.spawners.map(item => <option key={item.id} value={item.id}>{clean(item.name)} ({item.id})</option>)}</select></label></div>
    <p>{filtered.length} 件を表示 · 世界: {worldName} ({worldId})</p>
    <div className="wp-list">{filtered.map(item => <div key={item.placementId} className="wp-row"><input type="checkbox" aria-label={`${templateName(item, catalog)} を削除対象に選択`} checked={selectedForDelete.includes(item.placementId)} onChange={event => setSelectedForDelete(current => event.target.checked ? [...current, item.placementId] : current.filter(id => id !== item.placementId))} /><button type="button" aria-pressed={selectedId === item.placementId} onClick={() => onSelected(item.placementId)}><strong><MinecraftText value={templateName(item, catalog)} /></strong><small>{item.kind} · {regionName(item)} · X {item.x} / Y {item.y} / Z {item.z} · {item.enabled ? '有効' : '無効'}</small><code>{item.templateId ?? item.placementId}</code></button></div>)}{!filtered.length && <p>条件に合う保存済み配置はありません。</p>}</div>
    <div className="wp-actions"><button className="button" disabled={!selectedForDelete.length} onClick={() => remove(selectedForDelete)}>選択した {selectedForDelete.length} 件を削除</button></div>
    {selected && <div className="wp-form"><h3>{selected.kind === 'spawner' ? 'スポナー' : selected.kind === 'npc' ? 'NPC' : '道標'}の配置を編集</h3><code>{selected.placementId}</code>
      {selected.kind !== 'waystone' && <label>マスター<select value={selected.templateId ?? ''} onChange={event => update({ templateId: event.target.value || null })}><option value="">選択してください</option>{options.map(item => <option key={item.id} value={item.id}>{clean(item.name)} ({item.id})</option>)}</select></label>}
      <div className="wp-coordinates">{(['x', 'y', 'z', 'yaw', 'pitch'] as const).map(key => <label key={key}>{key.toUpperCase()}<input type="number" step={key === 'yaw' || key === 'pitch' ? '0.1' : '1'} value={selected[key]} onChange={event => update({ [key]: Number(event.target.value) })} /></label>)}</div>
      <label><input type="checkbox" checked={selected.enabled} onChange={event => update({ enabled: event.target.checked })} />有効</label>
      {selected.kind === 'spawner' && <p>出現範囲: {catalog.spawners.find(item => item.id === selected.templateId)?.radiusMeters ?? 'マスター未選択'} m</p>}
      {selected.kind === 'waystone' && selected.waystone && <div className="wp-waystone"><label>道標 ID<input value={selected.waystone.id} onChange={event => update({ waystone: { ...selected.waystone!, id: event.target.value } })} /></label><label>表示名<input value={selected.waystone.name} onChange={event => update({ waystone: { ...selected.waystone!, name: event.target.value } })} /></label><label>アイコン<input value={selected.waystone.icon} onChange={event => update({ waystone: { ...selected.waystone!, icon: event.target.value } })} /></label><label>解放費用<input type="number" min="0" value={selected.waystone.unlockGold} onChange={event => update({ waystone: { ...selected.waystone!, unlockGold: Number(event.target.value) } })} /></label><label><input type="checkbox" checked={selected.waystone.lockEnabled} onChange={event => update({ waystone: { ...selected.waystone!, lockEnabled: event.target.checked } })} />解放条件を使用</label></div>}
      <div className="wp-actions"><button className="button" onClick={duplicate}>複製</button><button className="button" onClick={() => remove([selected.placementId])}>この配置を削除</button></div>
    </div>}
  </section>
}
