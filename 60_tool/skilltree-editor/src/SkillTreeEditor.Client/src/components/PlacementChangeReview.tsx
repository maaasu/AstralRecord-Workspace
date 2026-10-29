import { useId, useState } from 'react'
import type { WorldPlacement } from '../api/worldPlacementApi'
import type { PlacementChange, PlacementChangeKind } from '../state/worldPlacementEditing'
import type { PlacementCatalog } from './WorldPlacementEditor'
import { MinecraftText } from './MinecraftText'

const kinds = { added: '追加', changed: '変更', deleted: '削除' }
function name(item: WorldPlacement, catalog: PlacementCatalog) {
  return item.kind === 'waystone' ? item.waystone?.name || item.waystone?.id || '道標'
    : (item.kind === 'spawner' ? catalog.spawners : catalog.npcs).find(master => master.id === item.templateId)?.name ?? item.templateId ?? 'マスター未選択'
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
  const [limit, setLimit] = useState(50)
  const id = useId()
  const filtered = open ? changes.filter(change => !filter || change.kind === filter) : []
  return <section className="wp-change-review" aria-label="未保存の配置変更">
    <button className="button" aria-expanded={open} aria-controls={id} onClick={() => setOpen(value => !value)}>変更内容を確認（{changes.length} 件）</button>
    {open && <div id={id}>
      <p>検索・種類・地域の条件にかかわらず、読み込んだ全配置の確定済み変更を表示します。取り消しは「元に戻す」で復元できます。</p>
      {hasDrafts && <p className="wp-draft-note">未確定の数値入力はこの比較に含みません。入力を確定するかEscapeで取り消すまで、配置ごとの取り消しはできません。</p>}
      <label>変更の種類<select value={filter} onChange={event => { setFilter(event.target.value as PlacementChangeKind | ''); setLimit(50) }}><option value="">すべて</option>{Object.entries(kinds).map(([key, label]) => <option key={key} value={key}>{label}</option>)}</select></label>
      <div className="wp-change-list">{filtered.slice(0, limit).map(change => {
        const item = change.after ?? change.before!
        const before = change.before ? values(change.before) : {}, after = change.after ? values(change.after) : {}
        const fields = [...new Set([...Object.keys(before), ...Object.keys(after)])].filter(field => !change.before || !change.after || before[field] !== after[field])
        return <article className="wp-change" key={change.id} aria-label={`${kinds[change.kind]} ${change.id}`}>
          <h3>{kinds[change.kind]} · <MinecraftText value={name(item, catalog)} /></h3><code>{change.id}</code>
          {change.before && change.after && name(change.before, catalog) !== name(change.after, catalog) && <p>保存前: <MinecraftText value={name(change.before, catalog)} /> → 編集後: <MinecraftText value={name(change.after, catalog)} /></p>}
          <table><thead><tr><th scope="col">項目</th><th scope="col">保存前</th><th scope="col">編集後</th></tr></thead><tbody>{fields.map(field => <tr key={field}><th scope="row">{field}</th><td><MinecraftText value={before[field] ?? '—'} /></td><td><MinecraftText value={after[field] ?? '—'} /></td></tr>)}</tbody></table>
          <div className="wp-actions">{change.after && <button className="button" disabled={locked} onClick={() => onLocate(change.after!)}>この配置へ移動</button>}<button className="button" disabled={locked || hasDrafts} onClick={() => onRevert(change)}>この配置の変更を取り消す</button></div>
        </article>
      })}</div>
      <p>変更一覧 {Math.min(limit, filtered.length)} / {filtered.length} 件</p>{!filtered.length && <p>該当する確定済み変更はありません。</p>}{limit < filtered.length && <button className="button" onClick={() => setLimit(value => value + 50)}>変更をさらに50件表示</button>}
    </div>}
  </section>
}
