import { useEffect, useId, useMemo, useRef, useState } from 'react'
import type { WorldPlacement } from '../api/worldPlacementApi'
import type { PlacementChange, PlacementChangeKind } from '../state/worldPlacementEditing'
import type { PlacementCatalog } from './WorldPlacementEditor'
import { MinecraftText } from './MinecraftText'
import { placementSearchText, placementSearchWords } from '../data/placementSearch'

const kinds = { added: '追加', changed: '変更', deleted: '削除' }
type MasterNames = { spawner: Map<string, string>; npc: Map<string, string> }
function name(item: WorldPlacement, names: MasterNames) {
  return item.kind === 'waystone' ? item.waystone?.name || item.waystone?.id || '道標'
    : names[item.kind].get(item.templateId ?? '') ?? item.templateId ?? 'マスター未選択'
}
function values(item: WorldPlacement): Record<string, string> {
  return { '種類': { spawner: 'スポナー', npc: 'NPC', waystone: '道標' }[item.kind], 'ワールドID': item.worldId, 'ワールド名': item.worldName,
    'マスターID': item.templateId ?? 'なし', X: String(item.x), Y: String(item.y), Z: String(item.z), '向き（Yaw）': String(item.yaw), '傾き（Pitch）': String(item.pitch), '有効': item.enabled ? '有効' : '無効',
    ...(item.waystone ? { '道標ID': item.waystone.id, '表示名': item.waystone.name, '解放条件': item.waystone.lockEnabled ? '使用' : '不使用', '解放費用': String(item.waystone.unlockGold), 'アイコン': item.waystone.icon, '作成日時': item.waystone.createdAt ?? 'なし', '作成者': item.waystone.createdBy } : {}) }
}
export function PlacementChangeReview({ changes, catalog, locked, hasDrafts, onLocate, onRevert }: {
  changes: PlacementChange[]; catalog: PlacementCatalog; locked: boolean; hasDrafts: boolean
  onLocate: (item: WorldPlacement) => void; onRevert: (change: PlacementChange) => void
}) {
  const [open, setOpen] = useState(false)
  const [filter, setFilter] = useState<PlacementChangeKind | ''>('')
  const [query, setQuery] = useState('')
  const [page, setPage] = useState(0)
  const list = useRef<HTMLDivElement>(null)
  const id = useId()
  const masterNames = useMemo(() => {
    const names: MasterNames = { spawner: new Map(), npc: new Map() }
    if (open) for (const kind of ['spawner', 'npc'] as const) {
      for (const master of kind === 'spawner' ? catalog.spawners : catalog.npcs) {
        if (!names[kind].has(master.id)) names[kind].set(master.id, master.name)
      }
    }
    return names
  }, [catalog.spawners, catalog.npcs, open])
  const words = useMemo(() => placementSearchWords(query), [query])
  const searching = words.length > 0
  // Only build searchable text while the open review actually has a query.
  // Both sides remain searchable, including deleted placements and renamed masters.
  const searchIndex = useMemo(() => !open || !searching ? null : new Map(changes.map(change => [change,
    placementSearchText([change.id, ...[change.before, change.after].flatMap(item => item
      ? [name(item, masterNames), item.templateId ?? '', item.waystone?.id ?? '', item.worldId, item.worldName, item.x, item.y, item.z] : [])].join(' ')),
  ])), [changes, masterNames, open, searching])
  const filtered = useMemo(() => open ? changes.filter(change => (!filter || change.kind === filter) &&
    words.every(word => searchIndex!.get(change)!.includes(word))) : [], [changes, filter, open, words, searchIndex])
  const pageCount = Math.max(1, Math.ceil(filtered.length / 50))
  const currentPage = Math.min(page, pageCount - 1)
  useEffect(() => { if (open) setPage(current => Math.min(current, pageCount - 1)) }, [open, pageCount])
  useEffect(() => { if (list.current) list.current.scrollTop = 0 }, [currentPage, filter, query, open])
  return <section className="wp-change-review" aria-label="未保存の配置変更">
    <button className="button" aria-expanded={open} aria-controls={id} onClick={() => setOpen(value => !value)}>変更内容を確認（{changes.length} 件）</button>
    {open && <div id={id}>
      <p>地図の検索・種類・地域の条件にかかわらず、読み込んだ全配置の確定済み変更を表示します。取り消しは「元に戻す」で復元できます。</p>
      {hasDrafts && <p className="wp-draft-note">未確定の数値入力はこの比較に含みません。入力を確定するかEscapeで取り消すまで、配置ごとの取り消しはできません。</p>}
      <div className="wp-change-filters"><label>変更の種類<select value={filter} onChange={event => { setFilter(event.target.value as PlacementChangeKind | ''); setPage(0) }}><option value="">すべて</option>{Object.entries(kinds).map(([key, label]) => <option key={key} value={key}>{label}</option>)}</select></label>
      <label>変更を検索<input value={query} aria-describedby={`${id}-search-help`} placeholder="名前・配置ID・マスターID・ワールド・座標" onChange={event => { setQuery(event.target.value); setPage(0) }} /></label></div>
      <p id={`${id}-search-help`}>空白区切りですべての語を含む変更を検索します。保存前と編集後の名前・ID・ワールド・X/Y/Zが対象です。地図の表示条件や保存対象は変えません。</p>
      <div className="wp-actions" role="group" aria-label="変更一覧のページ">
        <button className="button" aria-controls={`${id}-list`} disabled={currentPage === 0} onClick={() => setPage(currentPage - 1)}>前の変更ページ</button>
        <span role="status">変更一覧 {filtered.length ? `${currentPage * 50 + 1}–${Math.min((currentPage + 1) * 50, filtered.length)}` : '0'} / {filtered.length} 件 · {currentPage + 1} / {pageCount} ページ · 全変更 {changes.length} 件</span>
        <button className="button" aria-controls={`${id}-list`} disabled={currentPage + 1 >= pageCount} onClick={() => setPage(currentPage + 1)}>次の変更ページ</button>
      </div>
      <div id={`${id}-list`} ref={list} className="wp-change-list">{filtered.slice(currentPage * 50, (currentPage + 1) * 50).map(change => {
        const item = change.after ?? change.before!
        const before = change.before ? values(change.before) : {}, after = change.after ? values(change.after) : {}
        const fields = [...new Set([...Object.keys(before), ...Object.keys(after)])].filter(field => !change.before || !change.after || before[field] !== after[field])
        return <article className="wp-change" key={change.id} aria-label={`${kinds[change.kind]} ${change.id}`}>
          <h3>{kinds[change.kind]} · <MinecraftText value={name(item, masterNames)} /></h3><code>{change.id}</code>
          {change.before && change.after && name(change.before, masterNames) !== name(change.after, masterNames) && <p>保存前: <MinecraftText value={name(change.before, masterNames)} /> → 編集後: <MinecraftText value={name(change.after, masterNames)} /></p>}
          <table><thead><tr><th scope="col">項目</th><th scope="col">保存前</th><th scope="col">編集後</th></tr></thead><tbody>{fields.map(field => <tr key={field}><th scope="row">{field}</th><td><MinecraftText value={before[field] ?? '—'} /></td><td><MinecraftText value={after[field] ?? '—'} /></td></tr>)}</tbody></table>
          <div className="wp-actions">{change.after && <button className="button" disabled={locked} onClick={() => onLocate(change.after!)}>この配置へ移動</button>}<button className="button" disabled={locked || hasDrafts} onClick={() => onRevert(change)}>この配置の変更を取り消す</button></div>
        </article>
      })}</div>
      {!filtered.length && <p>該当する確定済み変更はありません。</p>}
    </div>}
  </section>
}
