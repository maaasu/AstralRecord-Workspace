import { Fragment, useCallback, useEffect, useId, useMemo, useRef, useState } from 'react'
import type { PlanningWorld } from '../api/spawnerPlanningApi'
import { worldPlacementApi, type PlacementSnapshot, type WorldPlacement } from '../api/worldPlacementApi'
import { placementSearchText, placementSearchWords } from '../data/placementSearch'
import { stripMinecraftFormatting } from '../utils/minecraft'
import { MinecraftIcon } from './MinecraftIcon'
import { MinecraftText } from './MinecraftText'
import { ResizableTable } from './ResizableTable'
import { TeleporterEditPanel, type SavedWaystone, type TeleporterEditTarget } from './TeleporterEditPanel'

const nameOrder = new Intl.Collator('ja-JP', { numeric: true, sensitivity: 'base' })
const columns = [
  { id: 'name', label: '表示名', width: 190, minWidth: 110 },
  { id: 'world', label: 'ワールド', width: 140, minWidth: 95 },
  { id: 'coordinates', label: '座標 (X / Y / Z)', width: 165, minWidth: 120 },
  { id: 'enabled', label: '有効状態', width: 95, minWidth: 86 },
  { id: 'unlock', label: '解放条件・費用', width: 185, minWidth: 112 },
  { id: 'icon', label: 'アイコン', width: 175, minWidth: 95 },
  { id: 'actions', label: '操作', width: 80, minWidth: 70 },
]

export function TeleporterList({ worlds, openDisabled, editDisabled = false, onOpen, onSaved, onDirtyChange, onBusyChange }: {
  worlds: PlanningWorld[]
  openDisabled: boolean
  editDisabled?: boolean
  onOpen: (placement: WorldPlacement, revision: string) => void
  onSaved?: () => void
  onDirtyChange?: (dirty: boolean) => void
  onBusyChange?: (busy: boolean) => void
}) {
  const headingId = useId()
  const detailId = useId()
  const searchInput = useRef<HTMLInputElement>(null)
  const editOpener = useRef<HTMLButtonElement | null>(null)
  const [saved, setSaved] = useState<{ revision: string; waystones: SavedWaystone[] } | null>(null)
  const [reload, setReload] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [query, setQuery] = useState('')
  const [worldFilter, setWorldFilter] = useState('')
  const [enabledFilter, setEnabledFilter] = useState('')
  const [expandedId, setExpandedId] = useState<string | null>(null)
  const [editing, setEditing] = useState<TeleporterEditTarget | null>(null)
  const [draftDirty, setDraftDirty] = useState(false)
  const [notice, setNotice] = useState('')

  const acceptSnapshot = useCallback((snapshot: PlacementSnapshot) => {
    const waystones = snapshot.placements.filter((item): item is SavedWaystone => item.kind === 'waystone' && item.waystone != null)
    setSaved({ revision: snapshot.revision, waystones })
    setWorldFilter(current => waystones.some(item => item.worldId === current) ? current : '')
    setExpandedId(current => waystones.some(item => item.placementId === current) ? current : null)
    setError('')
  }, [])

  useEffect(() => {
    let cancelled = false
    void worldPlacementApi.read().then(snapshot => {
      if (!cancelled) acceptSnapshot(snapshot)
    }).catch(failure => {
      if (!cancelled) setError(failure instanceof Error ? failure.message : 'Dev のテレポーターを取得できませんでした。')
    }).finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [reload, acceptSnapshot])
  useEffect(() => { onDirtyChange?.(draftDirty) }, [draftDirty, onDirtyChange])
  useEffect(() => {
    if (editing || !editOpener.current) return
    const opener = editOpener.current
    editOpener.current = null
    if (opener.isConnected && !opener.disabled) opener.focus()
    else searchInput.current?.focus()
  }, [editing])

  const entries = useMemo(() => (saved?.waystones ?? []).map(placement => {
    const { name, id } = placement.waystone
    const plainName = stripMinecraftFormatting(name).trim()
    return {
      placement,
      name: plainName ? name : id,
      plainName: plainName || id,
      search: placementSearchText(`${name} ${id}`),
    }
  }).sort((left, right) => nameOrder.compare(left.plainName, right.plainName)
    || nameOrder.compare(left.placement.waystone.id, right.placement.waystone.id)), [saved])
  const mapNames = new Map(worlds.map(world => [world.id, world.name]))
  const worldLabel = ({ worldId, worldName }: WorldPlacement) => mapNames.get(worldId) || worldName || worldId
  const worldOptions = new Map<string, string>()
  for (const { placement } of entries) {
    if (!worldOptions.has(placement.worldId)) worldOptions.set(placement.worldId, worldLabel(placement))
  }
  const words = placementSearchWords(query)
  const filtered = entries.filter(({ placement, search }) => (!worldFilter || placement.worldId === worldFilter)
    && (!enabledFilter || String(placement.enabled) === enabledFilter)
    && words.every(word => search.includes(word)))

  function refresh() {
    setError('')
    setNotice('')
    setLoading(true)
    setReload(current => current + 1)
  }

  function closeEditor() {
    setEditing(null)
    setDraftDirty(false)
  }

  function savedEdit(snapshot: PlacementSnapshot) {
    acceptSnapshot(snapshot)
    closeEditor()
    setNotice('Dev にテレポーターを保存しました。ゲーム内への反映には /worldplacements reload が必要です。')
    onSaved?.()
  }

  return <section className="sp-teleporter-list" aria-labelledby={headingId}>
    <div className="sp-section-heading">
      <div>
        <h2 id={headingId}>テレポーター一覧</h2>
        <p className="sp-muted">Dev に保存済みの道標を表示名順に確認・編集できます。ゲーム内で登録した道標は「再読込」で取得できます。</p>
      </div>
      <button className="button" type="button" onClick={refresh} disabled={loading || editing !== null}>再読込</button>
    </div>
    <div className="sp-controls">
      <label>名前・道標IDで検索<input ref={searchInput} type="search" value={query} onChange={event => setQuery(event.target.value)} /></label>
      <label>ワールド<select value={worldFilter} onChange={event => setWorldFilter(event.target.value)}>
        <option value="">すべて</option>
        {[...worldOptions].sort((left, right) => nameOrder.compare(left[1], right[1])).map(([id, name]) => <option key={id} value={id}>{name}</option>)}
      </select></label>
      <label>有効状態<select value={enabledFilter} onChange={event => setEnabledFilter(event.target.value)}>
        <option value="">すべて</option>
        <option value="true">有効</option>
        <option value="false">無効</option>
      </select></label>
    </div>
    {error && <div className="sp-error" role="alert">
      <strong>テレポーター一覧を読み込めません</strong>
      <p>{error}</p>
      {saved && <p>前回取得した一覧を表示しています。</p>}
    </div>}
    {notice && <p role="status">{notice}</p>}
    {editDisabled && !editing && <p className="sp-muted">配置編集の未保存の変更や進行中の操作を終えるとテレポーターを編集できます。</p>}
    {editing && <TeleporterEditPanel initialTarget={editing} worldLabel={worldLabel(editing.placement)} disabled={editDisabled}
      onSnapshot={acceptSnapshot} onClose={closeEditor} onSaved={savedEdit} onDirtyChange={setDraftDirty}
      onBusyChange={busy => onBusyChange?.(busy)} />}
    <p className="sp-muted" role="status">
      {loading ? 'テレポーターを読み込んでいます…'
        : saved ? `${filtered.length.toLocaleString('ja-JP')} / ${entries.length.toLocaleString('ja-JP')} 件` : ''}
    </p>
    {saved && filtered.length > 0 && <ResizableTable label="保存済みテレポーター" storageKey="teleporter-list" columns={columns} className="sp-teleporter-table">
        <tbody>{filtered.map(({ placement, name }) => {
          const { placementId, worldId, x, y, z, yaw, pitch, enabled, waystone } = placement
          const expanded = expandedId === placementId
          const configuredIcon = waystone.icon?.trim()
          const icon = configuredIcon || 'BEACON'
          const unlockGold = waystone.unlockGold.toLocaleString('ja-JP')
          return <Fragment key={placementId}>
            <tr>
              <th scope="row"><button type="button" className="button" aria-expanded={expanded} aria-controls={expanded ? detailId : undefined}
                onClick={() => setExpandedId(expanded ? null : placementId)}><MinecraftText value={name} /></button></th>
              <td>{worldLabel(placement)}</td>
              <td className="sp-teleporter-coordinates">{x} / {y} / {z}</td>
              <td>{enabled ? '有効' : '無効'}</td>
              <td>{waystone.lockEnabled ? `あり · ${unlockGold} ゴールド` : `なし · ${unlockGold} ゴールド（未使用）`}</td>
              <td><span className="sp-teleporter-icon"><MinecraftIcon icon={icon} /><code>{icon}</code>{!configuredIcon && '（既定）'}</span></td>
              <td><button className="button" type="button" aria-label={`${stripMinecraftFormatting(name)}を編集`} disabled={editDisabled || loading || editing !== null}
                onClick={event => {
                  editOpener.current = event.currentTarget
                  setNotice('')
                  setEditing({ placement, revision: saved.revision })
                }}>編集</button></td>
            </tr>
            {expanded && <tr id={detailId} className="sp-teleporter-detail"><td colSpan={columns.length}>
              <dl>
                <div><dt>道標ID</dt><dd><code>{waystone.id}</code></dd></div>
                <div><dt>色コード付きの名前</dt><dd><code>{waystone.name}</code></dd></div>
                <div><dt>向き</dt><dd>Yaw {yaw}° / Pitch {pitch}°</dd></div>
              </dl>
              {mapNames.has(worldId) ? <>
                <button type="button" className="button" disabled={openDisabled || loading || editing !== null} onClick={() => onOpen(placement, saved.revision)}>配置編集で開く</button>
                {openDisabled && <p className="sp-muted">未保存の編集や進行中の操作を終えると配置編集で開けます。</p>}
              </> : <p className="sp-muted">このワールドの地図は取り込まれていません。</p>}
            </td></tr>}
          </Fragment>
        })}</tbody>
    </ResizableTable>}
    {!loading && saved && filtered.length === 0 && <p className="sp-muted">{entries.length
      ? '条件に一致するテレポーターはありません。' : 'Dev に保存済みのテレポーターはありません。'}</p>}
  </section>
}
