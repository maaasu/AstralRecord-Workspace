import { useState } from 'react'
import { createPortal } from 'react-dom'
import type { WorldPlacement } from '../api/worldPlacementApi'
import type { PlacementCatalog } from './WorldPlacementEditor'
import { MinecraftText } from './MinecraftText'
import { Modal } from './Modal'

export type PlacementBulkAction = 'delete' | 'enable' | 'disable'
const titles = { delete: '配置の削除を確認', enable: '配置の有効化を確認', disable: '配置の無効化を確認' }
const actions = { delete: '削除対象にする', enable: '有効にする', disable: '無効にする' }

export function PlacementBulkConfirmation({ action, items, matchingIds, catalog, stale, onCancel, onConfirm }: {
  action: PlacementBulkAction; items: WorldPlacement[]; matchingIds: ReadonlySet<string>; catalog: PlacementCatalog
  stale: boolean; onCancel: () => void; onConfirm: () => void
}) {
  const [outsideOnly, setOutsideOnly] = useState(false)
  const [page, setPage] = useState(0)
  const outsideCount = items.filter(item => !matchingIds.has(item.placementId)).length
  const listed = outsideOnly ? items.filter(item => !matchingIds.has(item.placementId)) : items
  const pages = Math.max(1, Math.ceil(listed.length / 50))
  return createPortal(<Modal ariaLabel={titles[action]} className="wp-bulk-confirmation" onClose={onCancel}>
    <header><h2>{titles[action]}</h2><button type="button" className="button" onClick={onCancel}>キャンセル</button></header>
    <div className="wp-bulk-confirmation-body">
    <p className="wp-bulk-scope">合計 {items.length} 件 · 現在の表示条件内 {items.length - outsideCount} 件 · 表示条件外 {outsideCount} 件</p>
    <p>下記の全対象を{actions[action]}操作です。確定後も編集中の内容だけを変更します。Dev への反映には保存が必要です。</p>
    {stale && <p className="wp-error" role="alert">確認を開いた後にワールド・編集内容・表示条件・選択状態が変わったため実行できません。キャンセルして対象を確認し直してください。</p>}
    {outsideCount > 0 && <><label><input type="checkbox" checked={outsideOnly} onChange={event => { setOutsideOnly(event.target.checked); setPage(0) }} />表示条件外の対象だけを確認</label><p>一覧の絞り込みは確認用です。確定する対象は合計 {items.length} 件のままです。</p></>}
    <div className="wp-bulk-targets" aria-label="操作対象の配置">{listed.slice(page * 50, (page + 1) * 50).map(item => {
      const master = (item.kind === 'spawner' ? catalog.spawners : catalog.npcs).find(candidate => candidate.id === item.templateId)
      const name = item.kind === 'waystone' ? item.waystone?.name || item.waystone?.id || '道標' : master?.name ?? item.templateId ?? 'マスター未選択'
      const levels = item.kind === 'spawner' ? catalog.spawners.find(candidate => candidate.id === item.templateId)?.levels ?? [] : []
      return <article className="wp-bulk-target" key={item.placementId} aria-label={`対象 ${item.placementId}`}>
        <h3><MinecraftText value={name} /></h3>
        <p>{item.kind === 'spawner' ? `出現レベル: ${levels.length ? levels.join(' / ') : '未設定'}` : item.kind === 'npc' ? 'NPC' : '道標'} · {matchingIds.has(item.placementId) ? '現在の表示条件内' : '表示条件外'}</p>
        <p>X {item.x} / Y {item.y} / Z {item.z} · ワールド: {item.worldName} ({item.worldId})</p>
        <p>配置ID: <code>{item.placementId}</code> · マスターID: <code>{item.templateId ?? item.waystone?.id ?? 'なし'}</code></p>
        <p>有効状態: {item.enabled ? '有効' : '無効'} → {action === 'delete' ? '削除対象' : action === 'enable' ? '有効' : '無効'}</p>
      </article>
    })}</div>
    <div className="wp-actions"><span>対象一覧 {listed.length ? page * 50 + 1 : 0}〜{Math.min((page + 1) * 50, listed.length)} / {listed.length} 件 · {page + 1} / {pages} ページ</span><button type="button" className="button" disabled={page === 0} onClick={() => setPage(value => value - 1)}>前の50件</button><button type="button" className="button" disabled={page + 1 >= pages} onClick={() => setPage(value => value + 1)}>次の50件</button></div>
    </div>
    <footer className="wp-actions"><button type="button" className="button" onClick={onCancel}>戻って確認する</button><button type="button" className="button" disabled={stale} onClick={onConfirm}>{items.length} 件を{actions[action]}</button></footer>
  </Modal>, document.body)
}
