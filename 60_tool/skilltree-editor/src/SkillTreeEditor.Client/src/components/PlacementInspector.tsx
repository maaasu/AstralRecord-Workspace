import type { NodeMaster, StructureDocument } from '../types/editor'

interface PlacementInspectorProps {
  nodeId: string | null
  structure: StructureDocument
  master: NodeMaster | null
  onChange: (structure: StructureDocument) => void
  onEditMaster: (node: NodeMaster) => void
}

export function PlacementInspector({ nodeId, structure, master, onChange, onEditMaster }: PlacementInspectorProps) {
  const placement = structure.nodes.find((node) => node.nodeId === nodeId)
  if (!placement) {
    return (
      <section className="panel-section inspector empty-inspector">
        <h2>配置インスペクター</h2>
        <p className="muted">キャンバス上のノードを選択してください。</p>
      </section>
    )
  }

  const updateY = (value: number) => onChange({
    ...structure,
    nodes: structure.nodes.map((node) => node.nodeId === placement.nodeId ? { ...node, y: value } : node),
  })
  const remove = () => onChange({
    ...structure,
    rootNodeId: structure.rootNodeId === placement.nodeId ? '' : structure.rootNodeId,
    nodes: structure.nodes.filter((node) => node.nodeId !== placement.nodeId),
    edges: structure.edges.filter((edge) => edge.sourceNodeId !== placement.nodeId && edge.targetNodeId !== placement.nodeId),
  })

  return (
    <section className="panel-section inspector">
      <span className="eyebrow">PLACEMENT</span>
      <h2>{master?.name ?? '不明なノード'}</h2>
      <p className="muted">nodeId #{placement.nodeId}</p>
      <div className="coordinate-grid">
        <label>X<input value={placement.x} readOnly /></label>
        <label>Y<input
          type="number"
          step={1}
          min={-2147483648}
          max={2147483647}
          value={placement.y}
          onChange={(event) => updateY(Math.max(-2147483648, Math.min(2147483647, Math.round(Number(event.target.value) || 0))))}
        /></label>
        <label>Z<input value={placement.z} readOnly /></label>
      </div>
      <div className="button-stack">
        <button
          className="button"
          onClick={() => onChange({ ...structure, rootNodeId: placement.nodeId })}
          disabled={structure.rootNodeId === placement.nodeId}
        >
          {structure.rootNodeId === placement.nodeId ? 'ROOTに設定済み' : 'ROOTに設定'}
        </button>
        {master && <button className="button subtle" onClick={() => onEditMaster(master)}>マスター定義を編集</button>}
        <button className="button danger subtle" onClick={remove}>配置から削除</button>
      </div>
    </section>
  )
}
