import { useEffect, useId, useMemo, useRef, useState, type CSSProperties } from 'react'
import { ResizeHandle, usePanelSize } from './ResizeHandle'
import type { NodeMaster } from '../types/editor'
import { MinecraftText } from './MinecraftText'
import { MapEffectText, MapNodeIcon, NODE_KIND_LABEL, mapNodeCost, type NodeKind } from './skillTreeMapPresentation'
import { masterTagLabel } from '../data/masterTagPresentation'
import type { NodeListingEntry } from './useNodeListing'

interface NodeSidebarProps {
  nodes: NodeMaster[]
  entries: NodeListingEntry[]
  placedIds: Set<string>
  query: string
  selectedTag: string
  selectedKind?: NodeKind | ''
  onKindChange?: (value: NodeKind | '') => void
  paginationContext?: string
  onQueryChange: (value: string) => void
  onTagChange: (value: string) => void
  onEdit: (node: NodeMaster) => void
  onCreate: () => void
  onPlace?: (node: NodeMaster) => void
  /** 配置済みノードをキャンバスの中央へ表示する。 */
  onShow?: (node: NodeMaster) => void
  selectedNodeId?: string | null
  placementDisabled?: boolean
  iconRevision?: number
}

const NODE_PAGE_SIZE = 50
const FILTER_KINDS = ['small', 'notable', 'keystone', 'gateway', 'skill'] as const

export function NodeSidebar({
  nodes,
  entries,
  placedIds,
  query,
  selectedTag,
  selectedKind = '',
  onKindChange,
  paginationContext = '',
  onQueryChange,
  onTagChange,
  onEdit,
  onCreate,
  onPlace,
  onShow,
  selectedNodeId = null,
  placementDisabled = false,
  iconRevision = 0,
}: NodeSidebarProps) {
  const searchHelpId = useId()
  const [groupHeight, setGroupHeight] = usePanelSize('tree-node-groups', 280, 100, 1000)
  const pageContext = JSON.stringify([query, selectedTag, selectedKind, paginationContext])
  const tags = useMemo(() => [...new Set(nodes.flatMap(node => node.tags ?? []))].sort((a, b) => a.localeCompare(b, 'ja')), [nodes])
  const { unplaced, placed } = useMemo(() => {
    const unplaced: NodeListingEntry[] = [], placed: NodeListingEntry[] = []
    for (const entry of entries) (placedIds.has(entry.node.nodeId) ? placed : unplaced).push(entry)
    return { unplaced, placed }
  }, [entries, placedIds])

  return (
    <aside className="sidebar">
      <div className="sidebar-header">
        <div>
          <span className="eyebrow">ノードマスター</span>
          <h2>ノード一覧</h2>
        </div>
        <button className="button primary compact" onClick={onCreate}>＋ 新規</button>
      </div>
      <div className="filters">
        <input
          type="search"
          aria-label="ノードを検索"
          aria-describedby={searchHelpId}
          value={query}
          onChange={(event) => onQueryChange(event.target.value)}
          placeholder="例: 火球 CP 冒険者"
        />
        <small id={searchHelpId} className="node-search-help">ID・名前・タグ・効果・コストを検索します。空白で区切ると、すべての語を含むノードを表示します。</small>
        <select aria-label="ノードのタグ" value={selectedTag} onChange={(event) => onTagChange(event.target.value)}>
          <option value="">すべてのタグ</option>
          {tags.map((tag) => <option key={tag} value={tag}>{masterTagLabel(tag, true)}</option>)}
        </select>
        <select aria-label="ノードの種類" value={selectedKind} onChange={(event) => onKindChange?.(event.target.value as NodeKind | '')}>
          <option value="">すべての種類</option>
          {FILTER_KINDS.map(kind => <option key={kind} value={kind}>{NODE_KIND_LABEL[kind]}</option>)}
        </select>
      </div>
      <div className="node-groups" data-split={unplaced.length > 0 && placed.length > 0} style={{ '--node-group-height': `${groupHeight}px` } as CSSProperties}>
      <NodeGroup label="未配置" pageContext={pageContext} entries={unplaced} placedIds={placedIds} selectedNodeId={selectedNodeId} onEdit={onEdit} onPlace={onPlace} placementDisabled={placementDisabled} iconRevision={iconRevision} />
      {unplaced.length > 0 && placed.length > 0 && <ResizeHandle label="未配置と配置済み一覧の高さを変更" size={groupHeight} min={100} max={1000} defaultSize={280} minRemaining={100} orientation="horizontal" onResize={setGroupHeight} />}
      <NodeGroup label="配置済み" pageContext={pageContext} entries={placed} placedIds={placedIds} selectedNodeId={selectedNodeId} onEdit={onEdit} onShow={onShow} placementDisabled={placementDisabled} iconRevision={iconRevision} />
      </div>
    </aside>
  )
}

function NodeGroup({
  label,
  pageContext,
  entries,
  placedIds,
  selectedNodeId,
  onEdit,
  onPlace,
  onShow,
  placementDisabled,
  iconRevision,
}: {
  label: string
  pageContext: string
  entries: NodeListingEntry[]
  placedIds: Set<string>
  selectedNodeId: string | null
  onEdit: (node: NodeMaster) => void
  onPlace?: (node: NodeMaster) => void
  onShow?: (node: NodeMaster) => void
  placementDisabled?: boolean
  iconRevision: number
}) {
  const headingId = useId()
  const listRef = useRef<HTMLDivElement>(null)
  const firstEditRef = useRef<HTMLButtonElement>(null)
  const focusAfterPaging = useRef(false)
  const [pagination, setPagination] = useState({ context: pageContext, page: 0 })
  const pageCount = Math.max(1, Math.ceil(entries.length / NODE_PAGE_SIZE))
  const page = pagination.context === pageContext ? Math.min(pagination.page, pageCount - 1) : 0
  // 件数が戻っても、縮小前の存在しないページへ飛ばないよう補正値を保持する。
  if (pagination.context !== pageContext || pagination.page !== page) setPagination({ context: pageContext, page })
  const pageStart = page * NODE_PAGE_SIZE
  const visibleEntries = entries.slice(pageStart, pageStart + NODE_PAGE_SIZE)
  useEffect(() => {
    if (listRef.current) listRef.current.scrollTop = 0
    if (!focusAfterPaging.current) return
    focusAfterPaging.current = false
    firstEditRef.current?.focus({ preventScroll: true })
    firstEditRef.current?.scrollIntoView?.({ block: 'nearest' })
  }, [page, pageContext])
  const changePage = (nextPage: number) => {
    focusAfterPaging.current = true
    setPagination({ context: pageContext, page: nextPage })
  }
  return (
    <section className={`node-group ${entries.length === 0 ? 'empty' : ''}`} aria-labelledby={headingId}>
      <h3 id={headingId}>{label} {entries.length}</h3>
      {pageCount > 1 && <div className="node-pagination" role="group" aria-label={`${label}ノードのページ`}>
        <button className="button compact subtle" aria-label={`${label}ノードの前のページ`} disabled={page === 0} onClick={() => changePage(page - 1)}>前へ</button>
        <span role="status">{pageStart + 1}–{pageStart + visibleEntries.length}件 · {page + 1} / {pageCount}ページ</span>
        <button className="button compact subtle" aria-label={`${label}ノードの次のページ`} disabled={page === pageCount - 1} onClick={() => changePage(page + 1)}>次へ</button>
      </div>}
      <div className="node-list" ref={listRef}>
        {visibleEntries.map(({ node, name, effects, className, kind, tooltip }, index) => {
          return <article
            className={`node-list-item tree-map-list-row ${placedIds.has(node.nodeId) ? 'placed' : ''} ${node.nodeId === selectedNodeId ? 'selected' : ''}`}
            aria-current={node.nodeId === selectedNodeId ? 'true' : undefined}
            key={node.nodeId}
            title={tooltip}
            draggable={!placedIds.has(node.nodeId)}
            onDragStart={(event) => {
              event.dataTransfer.setData('application/x-astral-node', node.nodeId)
              event.dataTransfer.effectAllowed = 'copy'
            }}
            onDoubleClick={() => onEdit(node)}
          >
            <MapNodeIcon node={node} kind={kind} size={36} revision={iconRevision} />
            <div className="tree-map-list-copy">
              <strong><MinecraftText value={node.name} /></strong>
              <small>#{node.nodeId} · {className} · {mapNodeCost(node)} · {NODE_KIND_LABEL[kind]}</small>
              <div className="tree-map-list-effects">
                {effects.slice(0, 2).map((effect, index) => (
                  <span key={index}><MapEffectText effect={effect} /></span>
                ))}
                {effects.length > 2 && <span className="tree-map-muted">ほか {effects.length - 2} 件</span>}
              </div>
            </div>
            <div className="node-list-actions">
              {onPlace && <button className="button compact subtle" aria-label={`${name}を座標指定で配置`} disabled={placementDisabled} onClick={() => onPlace(node)}>配置</button>}
              {onShow && <button className="button compact subtle" aria-label={`${name}をキャンバスで表示`} title="キャンバスの中央に表示して選択" disabled={placementDisabled} onClick={() => onShow(node)}>表示</button>}
              <button className="text-button" ref={index === 0 ? firstEditRef : undefined} onClick={() => onEdit(node)}>編集</button>
            </div>
          </article>
        })}
        {entries.length === 0 && <p className="empty-state">該当ノードはありません。</p>}
      </div>
    </section>
  )
}
