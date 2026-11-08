import { Fragment, useEffect, useId, useMemo, useState } from 'react'
import type { PlanningWorld } from '../api/spawnerPlanningApi'
import { worldPlacementApi, type WorldPlacement } from '../api/worldPlacementApi'
import { placementSearchText, placementSearchWords } from '../data/placementSearch'
import { stripMinecraftFormatting } from '../utils/minecraft'
import { MinecraftIcon } from './MinecraftIcon'
import { MinecraftText } from './MinecraftText'

type SavedWaystone = WorldPlacement & { waystone: NonNullable<WorldPlacement['waystone']> }
const nameOrder = new Intl.Collator('ja-JP', { numeric: true, sensitivity: 'base' })

export function TeleporterList({ worlds, openDisabled, onOpen }: {
  worlds: PlanningWorld[]
  openDisabled: boolean
  onOpen: (placement: WorldPlacement, revision: string) => void
}) {
  const headingId = useId()
  const detailId = useId()
  const [saved, setSaved] = useState<{ revision: string; waystones: SavedWaystone[] } | null>(null)
  const [reload, setReload] = useState(0)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [query, setQuery] = useState('')
  const [worldFilter, setWorldFilter] = useState('')
  const [enabledFilter, setEnabledFilter] = useState('')
  const [expandedId, setExpandedId] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    void worldPlacementApi.read().then(snapshot => {
      if (cancelled) return
      const waystones = snapshot.placements.filter((item): item is SavedWaystone => item.kind === 'waystone' && item.waystone != null)
      setSaved({ revision: snapshot.revision, waystones })
      setWorldFilter(current => waystones.some(item => item.worldId === current) ? current : '')
      setExpandedId(current => waystones.some(item => item.placementId === current) ? current : null)
    }).catch(failure => {
      if (!cancelled) setError(failure instanceof Error ? failure.message : 'Dev のテレポーターを取得できませんでした。')
    }).finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [reload])

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
  const worldOptions = new Map<string, string>()
  for (const { placement } of entries) {
    const { worldId, worldName } = placement
    if (!worldOptions.has(worldId)) worldOptions.set(worldId, mapNames.get(worldId) || worldName || worldId)
  }
  const words = placementSearchWords(query)
  const filtered = entries.filter(({ placement, search }) => (!worldFilter || placement.worldId === worldFilter)
    && (!enabledFilter || String(placement.enabled) === enabledFilter)
    && words.every(word => search.includes(word)))

  function refresh() {
    setError('')
    setLoading(true)
    setReload(current => current + 1)
  }

  return <section className="sp-teleporter-list" aria-labelledby={headingId}>
    <div className="sp-section-heading">
      <div>
        <h2 id={headingId}>テレポーター一覧</h2>
        <p className="sp-muted">Dev に保存済みの道標を表示名順に確認できます。ゲーム内で登録した道標は「再読込」で取得できます。</p>
      </div>
      <button className="button" type="button" onClick={refresh} disabled={loading}>再読込</button>
    </div>
    <div className="sp-controls">
      <label>名前・道標IDで検索<input type="search" value={query} onChange={event => setQuery(event.target.value)} /></label>
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
    <p className="sp-muted" role="status">
      {loading ? 'テレポーターを読み込んでいます…'
        : saved ? `${filtered.length.toLocaleString('ja-JP')} / ${entries.length.toLocaleString('ja-JP')} 件` : ''}
    </p>
    {saved && filtered.length > 0 && <div className="sp-teleporter-table" role="region" aria-label="テレポーター一覧の表" tabIndex={0}>
      <table aria-label="保存済みテレポーター">
        <thead><tr>
          <th scope="col">表示名</th><th scope="col">ワールド</th><th scope="col">座標 (X / Y / Z)</th>
          <th scope="col">有効状態</th><th scope="col">解放条件・費用</th><th scope="col">アイコン</th>
        </tr></thead>
        <tbody>{filtered.map(({ placement, name }) => {
          const { placementId, worldId, worldName, x, y, z, yaw, pitch, enabled, waystone } = placement
          const expanded = expandedId === placementId
          const configuredIcon = waystone.icon?.trim()
          const icon = configuredIcon || 'BEACON'
          const unlockGold = waystone.unlockGold.toLocaleString('ja-JP')
          return <Fragment key={placementId}>
            <tr>
              <th scope="row"><button type="button" className="button" aria-expanded={expanded} aria-controls={expanded ? detailId : undefined}
                onClick={() => setExpandedId(expanded ? null : placementId)}><MinecraftText value={name} /></button></th>
              <td>{mapNames.get(worldId) || worldName || worldId}</td>
              <td className="sp-teleporter-coordinates">{x} / {y} / {z}</td>
              <td>{enabled ? '有効' : '無効'}</td>
              <td>{waystone.lockEnabled ? `あり · ${unlockGold} ゴールド` : `なし · ${unlockGold} ゴールド（未使用）`}</td>
              <td><span className="sp-teleporter-icon"><MinecraftIcon icon={icon} /><code>{icon}</code>{!configuredIcon && '（既定）'}</span></td>
            </tr>
            {expanded && <tr id={detailId} className="sp-teleporter-detail"><td colSpan={6}>
              <dl>
                <div><dt>道標ID</dt><dd><code>{waystone.id}</code></dd></div>
                <div><dt>色コード付きの名前</dt><dd><code>{waystone.name}</code></dd></div>
                <div><dt>向き</dt><dd>Yaw {yaw}° / Pitch {pitch}°</dd></div>
              </dl>
              {mapNames.has(worldId) ? <>
                <button type="button" className="button" disabled={openDisabled || loading} onClick={() => onOpen(placement, saved.revision)}>配置編集で開く</button>
                {openDisabled && <p className="sp-muted">未保存の編集や進行中の操作を終えると配置編集で開けます。</p>}
              </> : <p className="sp-muted">このワールドの地図は取り込まれていません。</p>}
            </td></tr>}
          </Fragment>
        })}</tbody>
      </table>
    </div>}
    {!loading && saved && filtered.length === 0 && <p className="sp-muted">{entries.length
      ? '条件に一致するテレポーターはありません。' : 'Dev に保存済みのテレポーターはありません。'}</p>}
  </section>
}
