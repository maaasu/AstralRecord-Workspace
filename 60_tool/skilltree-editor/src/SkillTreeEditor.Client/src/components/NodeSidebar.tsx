import { useMemo } from 'react'
import type { NodeMaster, SkillMasterSummary } from '../types/editor'
import { MinecraftIcon } from './MinecraftIcon'
import { stripMinecraftFormatting } from '../utils/minecraft'
import { describeNodeCost, describeNodeEffects, nodeTooltip, type NodeEffectPresentation } from '../data/nodeEffectPresentation'
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
  iconRevision?: number
  skillMasters: readonly SkillMasterSummary[]
}

export function NodeSidebar({
  nodes,
  placedIds,
  query,
  selectedTag,
  onQueryChange,
  onTagChange,
  onEdit,
  onCreate,
  iconRevision = 0,
  skillMasters,
}: NodeSidebarProps) {
  const effectsByNodeId = useMemo(
    () => new Map(nodes.map((node) => [node.nodeId, describeNodeEffects(node, skillMasters)])),
    [nodes, skillMasters],
  )
  const tags = [...new Set(nodes.flatMap((node) => node.tags ?? []))].sort((a, b) => a.localeCompare(b, 'ja'))
  const filtered = nodes.filter((node) => {
    const keyword = query.trim().toLocaleLowerCase()
    const cost = describeNodeCost(node)
    const matchesKeyword = !keyword
      || node.nodeId.toLocaleLowerCase().includes(keyword)
      || node.name.toLocaleLowerCase().includes(keyword)
      || (node.tags ?? []).some((tag) => masterTagSearchText(tag).toLocaleLowerCase().includes(keyword))
      || cost.searchText.toLocaleLowerCase().includes(keyword)
      || (effectsByNodeId.get(node.nodeId) ?? []).some((effect) => effect.searchText.toLocaleLowerCase().includes(keyword))
    return matchesKeyword && (!selectedTag || node.tags?.includes(selectedTag))
  })
  const unplaced = filtered.filter((node) => !placedIds.has(node.nodeId))
  const placed = filtered.filter((node) => placedIds.has(node.nodeId))

  return (
    <aside className="sidebar">
      <div className="sidebar-header">
        <div>
          <span className="eyebrow">MASTER NODES</span>
          <h2>ノード一覧</h2>
        </div>
        <button className="button primary compact" onClick={onCreate}>＋ 新規</button>
      </div>
      <div className="filters">
        <input
          type="search"
          value={query}
          onChange={(event) => onQueryChange(event.target.value)}
          placeholder="ID・名前・タグを検索"
        />
        <select value={selectedTag} onChange={(event) => onTagChange(event.target.value)}>
          <option value="">すべてのタグ</option>
          {tags.map((tag) => <option key={tag} value={tag}>{masterTagLabel(tag, true)}</option>)}
        </select>
      </div>
      <NodeGroup title={`未配置 ${unplaced.length}`} nodes={unplaced} placedIds={placedIds} onEdit={onEdit} iconRevision={iconRevision} effectsByNodeId={effectsByNodeId} />
      <NodeGroup title={`配置済み ${placed.length}`} nodes={placed} placedIds={placedIds} onEdit={onEdit} iconRevision={iconRevision} effectsByNodeId={effectsByNodeId} />
    </aside>
  )
}

function NodeGroup({
  title,
  nodes,
  placedIds,
  onEdit,
  iconRevision,
  effectsByNodeId,
}: {
  title: string
  nodes: NodeMaster[]
  placedIds: Set<string>
  onEdit: (node: NodeMaster) => void
  iconRevision: number
  effectsByNodeId: ReadonlyMap<string, readonly NodeEffectPresentation[]>
}) {
  return (
    <section className="node-group">
      <h3>{title}</h3>
      <div className="node-list">
        {nodes.map((node) => {
          const effects = effectsByNodeId.get(node.nodeId) ?? []
          const cost = describeNodeCost(node)
          return <article
            className={`node-list-item ${placedIds.has(node.nodeId) ? 'placed' : ''}`}
            key={node.nodeId}
            title={nodeTooltipWithTags(node, effects)}
            draggable={!placedIds.has(node.nodeId)}
            onDragStart={(event) => {
              event.dataTransfer.setData('application/x-astral-node', node.nodeId)
              event.dataTransfer.effectAllowed = 'copy'
            }}
            onDoubleClick={() => onEdit(node)}
          >
            <MinecraftIcon icon={node.icon} revision={iconRevision} className="sidebar-node-icon" />
            <div className="node-id">#{node.nodeId} · {String(node.icon)}</div>
            <strong>{stripMinecraftFormatting(node.name)}</strong>
            <div className="tag-row">
              {(node.tags ?? []).slice(0, 3).map((tag) => (
                <span className="tag" title={masterTagTooltip(tag)} key={tag}>{masterTagLabel(tag)}</span>
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
            <button className="text-button" onClick={() => onEdit(node)}>編集</button>
          </article>
        })}
        {nodes.length === 0 && <p className="empty-state">該当ノードはありません。</p>}
      </div>
    </section>
  )
}

function nodeTooltipWithTags(node: NodeMaster, effects: readonly NodeEffectPresentation[]): string {
  const tags = node.tags ?? []
  return [
    nodeTooltip(node, effects),
    ...(tags.length > 0 ? ['', 'タグ:', ...tags.map(masterTagTooltip)] : []),
  ].join('\n')
}
