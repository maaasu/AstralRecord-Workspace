import { Fragment, useCallback, useEffect, useId, useMemo, useRef, useState } from 'react'
import type { PlanningWorld } from '../api/spawnerPlanningApi'
import { worldPlacementApi, type PlacementSnapshot, type WorldPlacement } from '../api/worldPlacementApi'
import { placementSearchText, placementSearchWords } from '../data/placementSearch'
import { stripMinecraftFormatting } from '../utils/minecraft'
import { MinecraftIcon } from './MinecraftIcon'
import { MinecraftText } from './MinecraftText'
import { ResetTableColumns, ResizableTable } from './ResizableTable'
import { TeleporterEditPanel, type SavedWaystone, type TeleporterEditTarget } from './TeleporterEditPanel'

const nameOrder = new Intl.Collator('ja-JP', { numeric: true, sensitivity: 'base' })
const tableLayoutKey = 'teleporter-list-v2'
const columns = [
  { id: 'name', label: '表示名', width: 308, minWidth: 144 },
  { id: 'world', label: 'ワールド', width: 162, minWidth: 96 },
  { id: 'coordinates', label: '座標 (X / Y / Z)', width: 168, minWidth: 116 },
  { id: 'enabled', label: '状態', width: 92, minWidth: 72 },
  { id: 'unlock', label: '解放費用', width: 138, minWidth: 90 },
  { id: 'icon', label: 'アイコン', width: 204, minWidth: 96 },
  { id: 'actions', label: '操作', width: 92, minWidth: 64 },
]

const fullWorldLabel = ({ worldId, worldName }: WorldPlacement) => worldName || worldId

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
  const worldLabel = (placement: WorldPlacement) => mapNames.get(placement.worldId)
    || fullWorldLabel(placement).split(/[\\/]+/).filter(Boolean).at(-1) || placement.worldId
  const worldOptions = new Map<string, string>()
  for (const { placement } of entries) {
    if (!worldOptions.has(placement.worldId)) worldOptions.set(placement.worldId, worldLabel(placement))
  }
  const worldLabelCounts = new Map<string, number>()
  for (const name of worldOptions.values()) worldLabelCounts.set(name, (worldLabelCounts.get(name) ?? 0) + 1)
  const words = placementSearchWords(query)
  const filtered = entries.filter(({ placement, search }) => (!worldFilter || placement.worldId === worldFilter)
    && (!enabledFilter || String(placement.enabled) === enabledFilter)
    && words.every(word => search.includes(word)))
  const hasFilters = Boolean(query || worldFilter || enabledFilter)
  const enabledCount = entries.filter(({ placement }) => placement.enabled).length

  function clearFilters() {
    setQuery('')
    setWorldFilter('')
    setEnabledFilter('')
    searchInput.current?.focus()
  }

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
    <header className="sp-teleporter-heading">
      <div>
        <span className="sp-teleporter-environment">Dev 配置</span>
        <h2 id={headingId}>テレポーター一覧</h2>
        <p>保存済みの道標を探して、座標・解放費用・アイコンを確認できます。</p>
      </div>
      {saved && <dl className="sp-teleporter-summary" aria-label="保存済み道標の集計">
        <div><dt>登録済み</dt><dd>{entries.length.toLocaleString('ja-JP')}</dd></div>
        <div><dt>ワールド</dt><dd>{worldOptions.size.toLocaleString('ja-JP')}</dd></div>
        <div><dt>有効な道標</dt><dd>{enabledCount.toLocaleString('ja-JP')}</dd></div>
      </dl>}
    </header>
    <div className="sp-teleporter-toolbar">
      <div className="sp-teleporter-filters">
        <label>名前・道標IDで検索<input ref={searchInput} type="search" placeholder="道標名または ID を入力" value={query} onChange={event => setQuery(event.target.value)} /></label>
        <label>ワールド<select value={worldFilter} onChange={event => setWorldFilter(event.target.value)}>
          <option value="">すべて</option>
          {[...worldOptions].sort((left, right) => nameOrder.compare(left[1], right[1])).map(([id, name]) => <option key={id} value={id}>
            {worldLabelCounts.get(name) === 1 ? name : `${name} (${id})`}
          </option>)}
        </select></label>
        <label>有効状態<select value={enabledFilter} onChange={event => setEnabledFilter(event.target.value)}>
          <option value="">すべて</option>
          <option value="true">有効</option>
          <option value="false">無効</option>
        </select></label>
        <button className="button subtle" type="button" onClick={clearFilters} disabled={!hasFilters}>条件をクリア</button>
        <button className="button" type="button" onClick={refresh} disabled={loading || editing !== null}>再読込</button>
      </div>
    </div>
    {error && <div className="sp-error" role="alert">
      <strong>テレポーター一覧を読み込めません</strong>
      <p>{error}</p>
      {saved && <p>前回取得した一覧を表示しています。</p>}
    </div>}
    {notice && <p role="status">{notice}</p>}
    {editDisabled && !editing && <p className="sp-muted">配置編集の未保存の変更や進行中の操作を終えるとテレポーターを編集できます。</p>}
    {editing && <TeleporterEditPanel initialTarget={editing} worldLabel={mapNames.get(editing.placement.worldId) || fullWorldLabel(editing.placement)} disabled={editDisabled}
      onSnapshot={acceptSnapshot} onClose={closeEditor} onSaved={savedEdit} onDirtyChange={setDraftDirty}
      onBusyChange={busy => onBusyChange?.(busy)} />}
    <div className="sp-teleporter-results">
      <p className="sp-teleporter-count" role="status">
        {loading ? '読込中…' : saved && <>
          <strong>{filtered.length.toLocaleString('ja-JP')} 件</strong>
          {hasFilters && <span> / 全 {entries.length.toLocaleString('ja-JP')} 件</span>}
          <span className="sp-teleporter-order">表示名順</span>
        </>}
      </p>
      {saved && <ResetTableColumns storageKey={tableLayoutKey} className="button subtle compact sp-teleporter-column-reset">列幅をリセット</ResetTableColumns>}
    </div>
    {saved && filtered.length > 0 && <ResizableTable label="保存済みテレポーター" storageKey={tableLayoutKey} columns={columns} className="sp-teleporter-table" fillWidth>
        <tbody>{filtered.map(({ placement, name, plainName }) => {
          const { placementId, worldId, x, y, z, yaw, pitch, enabled, waystone } = placement
          const expanded = expandedId === placementId
          const configuredIcon = waystone.icon?.trim()
          const icon = configuredIcon || 'BEACON'
          const unlockGold = waystone.unlockGold.toLocaleString('ja-JP')
          return <Fragment key={placementId}>
            <tr className={`sp-teleporter-row${expanded || editing?.placement.placementId === placementId ? ' is-selected' : ''}`}>
              <th scope="row"><button type="button" className="sp-teleporter-name" aria-label={plainName} title={plainName}
                aria-expanded={expanded} aria-controls={expanded ? detailId : undefined}
                onClick={() => setExpandedId(expanded ? null : placementId)}><MinecraftText value={name} /></button></th>
              <td className="sp-teleporter-world-cell">
                <span className="sp-teleporter-cell-label">ワールド</span>
                <span className="sp-teleporter-world" title={fullWorldLabel(placement)}>{worldLabel(placement)}</span>
              </td>
              <td className="sp-teleporter-coordinates">
                <span className="sp-teleporter-cell-label">座標 (X / Y / Z)</span>{x} / {y} / {z}
              </td>
              <td className="sp-teleporter-state-cell"><span className={`sp-teleporter-state${enabled ? ' is-enabled' : ''}`}>{enabled ? '有効' : '無効'}</span></td>
              <td className="sp-teleporter-unlock">
                <span className="sp-teleporter-cell-label">解放費用</span>
                <span className={waystone.lockEnabled ? 'sp-teleporter-gold' : 'sp-teleporter-no-fee'}>{waystone.lockEnabled ? `${unlockGold} ゴールド` : 'なし'}</span>
              </td>
              <td className="sp-teleporter-icon-cell"><span className="sp-teleporter-icon" title={configuredIcon ? icon : `${icon}（既定）`}>
                <MinecraftIcon icon={icon} className="sp-teleporter-icon-preview" />
                <span className="sp-teleporter-icon-label"><code>{icon}</code>{!configuredIcon && <small>既定</small>}</span>
              </span></td>
              <td className="sp-teleporter-action-cell"><button className="button sp-teleporter-edit-button" type="button" aria-label={`${plainName}を編集`} disabled={editDisabled || loading || editing !== null}
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
                <div><dt>ワールド名</dt><dd><code>{fullWorldLabel(placement)}</code></dd></div>
                <div><dt>向き</dt><dd>Yaw {yaw}° / Pitch {pitch}°</dd></div>
                <div><dt>解放条件・費用</dt><dd>{waystone.lockEnabled ? `あり · ${unlockGold} ゴールド` : `なし · ${unlockGold} ゴールド（未使用）`}</dd></div>
                <div><dt>アイコン</dt><dd><code>{icon}</code>{!configuredIcon && '（既定）'}</dd></div>
              </dl>
              {mapNames.has(worldId) ? <>
                <button type="button" className="button" disabled={openDisabled || loading || editing !== null} onClick={() => onOpen(placement, saved.revision)}>配置編集で開く</button>
                {openDisabled && <p className="sp-muted">未保存の編集や進行中の操作を終えると配置編集で開けます。</p>}
              </> : <p className="sp-muted">このワールドの地図は取り込まれていません。</p>}
            </td></tr>}
          </Fragment>
        })}</tbody>
    </ResizableTable>}
    {!loading && saved && filtered.length === 0 && <div className="sp-teleporter-empty">
      <strong>{entries.length ? '条件に一致するテレポーターはありません。' : 'Dev に保存済みのテレポーターはありません。'}</strong>
      {entries.length > 0 && <p>別の名前・道標 ID で検索するか、条件をクリアしてください。</p>}
    </div>}
  </section>
}
