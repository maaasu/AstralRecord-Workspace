import { useId, useMemo } from 'react'
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
const normalizeSearch = (text: string) => stripMinecraftFormatting(text).toLocaleLowerCase('ja-JP')

export function NodeSidebar({
  nodes,
  placedIds,
  query,
  selectedTag,
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
      <NodeGroup title={`未配置 ${unplaced.length}`} entries={unplaced} placedIds={placedIds} onEdit={onEdit} onPlace={onPlace} placementDisabled={placementDisabled} iconRevision={iconRevision} />
      <NodeGroup title={`配置済み ${placed.length}`} entries={placed} placedIds={placedIds} onEdit={onEdit} iconRevision={iconRevision} />
    </aside>
  )
}

function NodeGroup({
  title,
  entries,
  placedIds,
  onEdit,
  onPlace,
  placementDisabled,
  iconRevision,
}: {
  title: string
  entries: NodeListingEntry[]
  placedIds: Set<string>
  onEdit: (node: NodeMaster) => void
  onPlace?: (node: NodeMaster) => void
  placementDisabled?: boolean
  iconRevision: number
}) {
  return (
    <section className={`node-group ${entries.length === 0 ? 'empty' : ''}`}>
      <h3>{title}</h3>
      <div className="node-list">
        {entries.map(({ node, name, effects, cost, tags, tooltip }) => {
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
            <div className="node-list-actions">{onPlace && <button className="button compact subtle" aria-label={`${name}を座標指定で配置`} disabled={placementDisabled} onClick={() => onPlace(node)}>配置</button>}<button className="text-button" onClick={() => onEdit(node)}>編集</button></div>
          </article>
        })}
        {entries.length === 0 && <p className="empty-state">該当ノードはありません。</p>}
      </div>
    </section>
  )
}
