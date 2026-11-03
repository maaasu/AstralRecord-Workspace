import { useEffect, useId, useMemo, useRef, useState, type CSSProperties } from 'react'
import { ResizeHandle, usePanelSize } from './ResizeHandle'
import type { ClassMasterSummary, NodeMaster, SkillMasterSummary } from '../types/editor'
import { MinecraftIcon } from './MinecraftIcon'
import { stripMinecraftFormatting } from '../utils/minecraft'
import { describeNodeCost, describeNodeEffects, nodeTooltip, type NodeCostPresentation, type NodeEffectPresentation } from '../data/nodeEffectPresentation'
import { masterTagLabel, masterTagSearchText, masterTagTooltip } from '../data/masterTagPresentation'

interface NodeSidebarProps {
  nodes: NodeMaster[]
  placedIds: Set<string>
  query: string
  selectedTag: string
  paginationContext?: string
  onQueryChange: (value: string) => void
  onTagChange: (value: string) => void
  onEdit: (node: NodeMaster) => void
  onCreate: () => void
  onPlace?: (node: NodeMaster) => void
  placementDisabled?: boolean
  iconRevision?: number
  skillMasters: readonly SkillMasterSummary[]
  classMasters?: readonly ClassMasterSummary[]
}

interface NodeListingEntry {
  node: NodeMaster
  name: string
  effects: NodeEffectPresentation[]
  cost: NodeCostPresentation
  tags: { id: string; label: string; tooltip: string }[]
  tooltip: string
  searchText: string
}

const EMPTY_CLASS_MASTERS: readonly ClassMasterSummary[] = []
const NODE_PAGE_SIZE = 50
const normalizeSearch = (text: string) => stripMinecraftFormatting(text).toLocaleLowerCase('ja-JP')

export function NodeSidebar({
  nodes,
  placedIds,
  query,
  selectedTag,
  paginationContext = '',
  onQueryChange,
  onTagChange,
  onEdit,
  onCreate,
  onPlace,
  placementDisabled = false,
  iconRevision = 0,
  skillMasters,
  classMasters = EMPTY_CLASS_MASTERS,
}: NodeSidebarProps) {
  const searchHelpId = useId()
  const [groupHeight, setGroupHeight] = usePanelSize('tree-node-groups', 280, 100, 1000)
  const pageContext = JSON.stringify([query, selectedTag, paginationContext])
  const entries = useMemo<NodeListingEntry[]>(() => nodes.map(node => {
    const effects = describeNodeEffects(node, skillMasters)
    const cost = describeNodeCost(node, classMasters)
    const name = stripMinecraftFormatting(node.name)
    const tags = (node.tags ?? []).map(id => ({ id, label: masterTagLabel(id), tooltip: masterTagTooltip(id) }))
    return {
      node, name, effects, cost, tags,
      tooltip: [nodeTooltip(node, effects, classMasters), ...(tags.length ? ['', 'タグ:', ...tags.map(tag => tag.tooltip)] : [])].join('\n'),
      searchText: normalizeSearch([node.nodeId, name, ...(node.tags ?? []).map(masterTagSearchText), cost.searchText, ...effects.map(effect => effect.searchText)].join('\n')),
    }
  }), [nodes, skillMasters, classMasters])
  const tags = useMemo(() => [...new Set(nodes.flatMap(node => node.tags ?? []))].sort((a, b) => a.localeCompare(b, 'ja')), [nodes])
  const words = useMemo(() => normalizeSearch(query).trim().split(/\s+/u).filter(Boolean), [query])
  const filtered = useMemo(() => entries.filter(entry => (!selectedTag || entry.node.tags?.includes(selectedTag))
    && words.every(word => entry.searchText.includes(word))), [entries, selectedTag, words])
  const { unplaced, placed } = useMemo(() => {
    const unplaced: NodeListingEntry[] = [], placed: NodeListingEntry[] = []
    for (const entry of filtered) (placedIds.has(entry.node.nodeId) ? placed : unplaced).push(entry)
    return { unplaced, placed }
  }, [filtered, placedIds])

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
      </div>
      <div className="node-groups" data-split={unplaced.length > 0 && placed.length > 0} style={{ '--node-group-height': `${groupHeight}px` } as CSSProperties}>
      <NodeGroup label="未配置" pageContext={pageContext} entries={unplaced} placedIds={placedIds} onEdit={onEdit} onPlace={onPlace} placementDisabled={placementDisabled} iconRevision={iconRevision} />
      {unplaced.length > 0 && placed.length > 0 && <ResizeHandle label="未配置と配置済み一覧の高さを変更" size={groupHeight} min={100} max={1000} defaultSize={280} minRemaining={100} orientation="horizontal" onResize={setGroupHeight} />}
      <NodeGroup label="配置済み" pageContext={pageContext} entries={placed} placedIds={placedIds} onEdit={onEdit} iconRevision={iconRevision} />
      </div>
    </aside>
  )
}

function NodeGroup({
  label,
  pageContext,
  entries,
  placedIds,
  onEdit,
  onPlace,
  placementDisabled,
  iconRevision,
}: {
  label: string
  pageContext: string
  entries: NodeListingEntry[]
  placedIds: Set<string>
  onEdit: (node: NodeMaster) => void
  onPlace?: (node: NodeMaster) => void
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
        {visibleEntries.map(({ node, name, effects, cost, tags, tooltip }, index) => {
          return <article
            className={`node-list-item ${placedIds.has(node.nodeId) ? 'placed' : ''}`}
            key={node.nodeId}
            title={tooltip}
            draggable={!placedIds.has(node.nodeId)}
            onDragStart={(event) => {
              event.dataTransfer.setData('application/x-astral-node', node.nodeId)
              event.dataTransfer.effectAllowed = 'copy'
            }}
            onDoubleClick={() => onEdit(node)}
          >
            <MinecraftIcon icon={node.icon} revision={iconRevision} className="sidebar-node-icon" />
            <div className="node-id">#{node.nodeId} · {String(node.icon)}</div>
            <strong>{name}</strong>
            <div className="tag-row">
              {tags.slice(0, 3).map((tag) => (
                <span className="tag" title={tag.tooltip} key={tag.id}>{tag.label}</span>
              ))}
            </div>
            <div className="node-cost-preview" title={cost.detail}>
              <span className={`cost-chip ${cost.kind}`}>消費 {cost.title}</span>
            </div>
            {effects.length > 0 && (
              <div className="node-effect-preview">
                {effects.slice(0, 2).map((effect, index) => (
                  <span className={`node-effect ${effect.kind}`} key={`${effect.kind}-${index}`}>{effect.title}</span>
                ))}
                {effects.length > 2 && <span className="node-effect more">ほか{effects.length - 2}件</span>}
              </div>
            )}
            <div className="node-list-actions">{onPlace && <button className="button compact subtle" aria-label={`${name}を座標指定で配置`} disabled={placementDisabled} onClick={() => onPlace(node)}>配置</button>}<button className="text-button" ref={index === 0 ? firstEditRef : undefined} onClick={() => onEdit(node)}>編集</button></div>
          </article>
        })}
        {entries.length === 0 && <p className="empty-state">該当ノードはありません。</p>}
      </div>
    </section>
  )
}
