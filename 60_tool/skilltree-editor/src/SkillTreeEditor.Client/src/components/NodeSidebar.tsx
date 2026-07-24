import type { NodeMaster } from '../types/editor'

interface NodeSidebarProps {
  nodes: NodeMaster[]
  placedIds: Set<string>
  query: string
  selectedTag: string
  onQueryChange: (value: string) => void
  onTagChange: (value: string) => void
  onEdit: (node: NodeMaster) => void
  onCreate: () => void
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
}: NodeSidebarProps) {
  const tags = [...new Set(nodes.flatMap((node) => node.tags ?? []))].sort((a, b) => a.localeCompare(b, 'ja'))
  const filtered = nodes.filter((node) => {
    const keyword = query.trim().toLocaleLowerCase()
    const matchesKeyword = !keyword
      || node.nodeId.toLocaleLowerCase().includes(keyword)
      || node.name.toLocaleLowerCase().includes(keyword)
      || (node.tags ?? []).some((tag) => tag.toLocaleLowerCase().includes(keyword))
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
          {tags.map((tag) => <option key={tag} value={tag}>{tag}</option>)}
        </select>
      </div>
      <NodeGroup title={`未配置 ${unplaced.length}`} nodes={unplaced} placedIds={placedIds} onEdit={onEdit} />
      <NodeGroup title={`配置済み ${placed.length}`} nodes={placed} placedIds={placedIds} onEdit={onEdit} />
    </aside>
  )
}

function NodeGroup({
  title,
  nodes,
  placedIds,
  onEdit,
}: {
  title: string
  nodes: NodeMaster[]
  placedIds: Set<string>
  onEdit: (node: NodeMaster) => void
}) {
  return (
    <section className="node-group">
      <h3>{title}</h3>
      <div className="node-list">
        {nodes.map((node) => (
          <article
            className={`node-list-item ${placedIds.has(node.nodeId) ? 'placed' : ''}`}
            key={node.nodeId}
            draggable={!placedIds.has(node.nodeId)}
            onDragStart={(event) => {
              event.dataTransfer.setData('application/x-astral-node', node.nodeId)
              event.dataTransfer.effectAllowed = 'copy'
            }}
            onDoubleClick={() => onEdit(node)}
          >
            <div className="node-id">#{node.nodeId}</div>
            <strong>{node.name}</strong>
            <div className="tag-row">
              {(node.tags ?? []).slice(0, 3).map((tag) => <span className="tag" key={tag}>{tag}</span>)}
            </div>
            <button className="text-button" onClick={() => onEdit(node)}>編集</button>
          </article>
        ))}
        {nodes.length === 0 && <p className="empty-state">該当ノードはありません。</p>}
      </div>
    </section>
  )
}
