import { useCallback, useMemo, useState } from 'react'
import {
  Background,
  BackgroundVariant,
  Controls,
  Handle,
  MiniMap,
  Position,
  ReactFlow,
  ReactFlowProvider,
  useReactFlow,
  type Connection,
  type Edge,
  type EdgeChange,
  type Node,
  type NodeChange,
  type NodeProps,
} from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import type { NodeMaster, StructureDocument } from '../types/editor'

interface SkillTreeCanvasProps {
  structure: StructureDocument
  masters: NodeMaster[]
  onRecord: (structure: StructureDocument) => void
  onReplace: (structure: StructureDocument) => void
  onBeginTransaction: () => void
  onCommitTransaction: () => void
  onSelectedNode: (nodeId: string | null) => void
}

interface SkillNodeData extends Record<string, unknown> {
  label: string
  nodeId: string
  y: number
  root: boolean
}

const nodeTypes = { skill: SkillNode }
const BLOCK_SCALE = 36
const GRID: [number, number] = [BLOCK_SCALE, BLOCK_SCALE]

export function SkillTreeCanvas(props: SkillTreeCanvasProps) {
  return (
    <ReactFlowProvider>
      <CanvasInner {...props} />
    </ReactFlowProvider>
  )
}

function CanvasInner({
  structure,
  masters,
  onRecord,
  onReplace,
  onBeginTransaction,
  onCommitTransaction,
  onSelectedNode,
}: SkillTreeCanvasProps) {
  const { screenToFlowPosition } = useReactFlow()
  const [selectedIds, setSelectedIds] = useState<Set<string>>(() => new Set())
  const [selectedEdgeIds, setSelectedEdgeIds] = useState<Set<string>>(() => new Set())
  const masterMap = useMemo(() => new Map(masters.map((node) => [node.nodeId, node])), [masters])
  const nodes = useMemo<Node<SkillNodeData>[]>(() => structure.nodes.map((placement) => ({
      id: placement.nodeId,
      type: 'skill',
      selected: selectedIds.has(placement.nodeId),
      position: { x: placement.x * BLOCK_SCALE, y: placement.z * BLOCK_SCALE },
      data: {
        label: masterMap.get(placement.nodeId)?.name ?? `Unknown ${placement.nodeId}`,
        nodeId: placement.nodeId,
        y: placement.y,
        root: placement.nodeId === structure.rootNodeId,
      },
    })),
    [masterMap, selectedIds, structure.nodes, structure.rootNodeId],
  )
  const edges = useMemo<Edge[]>(() => structure.edges.map((edge) => ({
      id: edgeId(edge.sourceNodeId, edge.targetNodeId),
      source: edge.sourceNodeId,
      target: edge.targetNodeId,
      type: 'straight',
      className: 'skill-edge',
      selected: selectedEdgeIds.has(edgeId(edge.sourceNodeId, edge.targetNodeId)),
    })),
    [selectedEdgeIds, structure.edges],
  )

  const onNodesChange = useCallback((changes: NodeChange<Node<SkillNodeData>>[]) => {
    setSelectedIds((current) => {
      let next: Set<string> | null = null
      for (const change of changes) {
        if (change.type !== 'select') continue
        const selected = (next ?? current).has(change.id)
        if (selected === change.selected) continue
        next ??= new Set(current)
        if (change.selected) next.add(change.id)
        else next.delete(change.id)
      }
      return next ?? current
    })

    const positions = new Map<string, { x: number; z: number }>()
    for (const change of changes) {
      if (change.type === 'position' && change.position) {
        positions.set(change.id, {
          x: Math.round(change.position.x / BLOCK_SCALE),
          z: Math.round(change.position.y / BLOCK_SCALE),
        })
      }
    }
    if (!positions.size) return
    onReplace({
      ...structure,
      nodes: structure.nodes.map((placement) => {
        const position = positions.get(placement.nodeId)
        return position ? { ...placement, ...position } : placement
      }),
    })
  }, [onReplace, structure])

  const onEdgesChange = useCallback((changes: EdgeChange<Edge>[]) => {
    setSelectedEdgeIds((current) => {
      let next: Set<string> | null = null
      for (const change of changes) {
        if (change.type !== 'select') continue
        const selected = (next ?? current).has(change.id)
        if (selected === change.selected) continue
        next ??= new Set(current)
        if (change.selected) next.add(change.id)
        else next.delete(change.id)
      }
      return next ?? current
    })
  }, [])

  const connect = useCallback((connection: Connection) => {
    if (!connection.source || !connection.target || connection.source === connection.target) return
    const sourceNodeId = connection.source < connection.target ? connection.source : connection.target
    const targetNodeId = connection.source < connection.target ? connection.target : connection.source
    if (structure.edges.some((edge) => edgeId(edge.sourceNodeId, edge.targetNodeId) === edgeId(sourceNodeId, targetNodeId))) return
    onRecord({
      ...structure,
      edges: [...structure.edges, { sourceNodeId, targetNodeId }],
    })
  }, [onRecord, structure])

  const removeNodes = useCallback((deleted: Node[]) => {
    const ids = new Set(deleted.map((node) => node.id))
    if (!ids.size) return
    setSelectedIds((current) => {
      if (![...ids].some((id) => current.has(id))) return current
      const next = new Set(current)
      ids.forEach((id) => next.delete(id))
      return next
    })
    onRecord({
      ...structure,
      rootNodeId: ids.has(structure.rootNodeId) ? '' : structure.rootNodeId,
      nodes: structure.nodes.filter((node) => !ids.has(node.nodeId)),
      edges: structure.edges.filter((edge) => !ids.has(edge.sourceNodeId) && !ids.has(edge.targetNodeId)),
    })
    onSelectedNode(null)
  }, [onRecord, onSelectedNode, structure])

  const removeEdges = useCallback((deleted: Edge[]) => {
    const ids = new Set(deleted.map((edge) => edge.id))
    if (!ids.size) return
    setSelectedEdgeIds((current) => {
      if (![...ids].some((id) => current.has(id))) return current
      const next = new Set(current)
      ids.forEach((id) => next.delete(id))
      return next
    })
    onRecord({
      ...structure,
      edges: structure.edges.filter((edge) => !ids.has(edgeId(edge.sourceNodeId, edge.targetNodeId))),
    })
  }, [onRecord, structure])

  const selectionChanged = useCallback(({ nodes: selected }: { nodes: Node[] }) => {
    onSelectedNode(selected[0]?.id ?? null)
  }, [onSelectedNode])

  const validConnection = useCallback((connection: Edge | Connection) => Boolean(
    connection.source
    && connection.target
    && connection.source !== connection.target
    && !structure.edges.some((edge) => edgeId(edge.sourceNodeId, edge.targetNodeId) === edgeId(connection.source, connection.target)),
  ), [structure.edges])

  return (
    <div
      className="canvas"
      onDragOver={(event) => {
        event.preventDefault()
        event.dataTransfer.dropEffect = 'copy'
      }}
      onDrop={(event) => {
        event.preventDefault()
        const nodeId = event.dataTransfer.getData('application/x-astral-node')
        if (!nodeId || structure.nodes.some((node) => node.nodeId === nodeId)) return
        const position = screenToFlowPosition({ x: event.clientX, y: event.clientY }, { snapToGrid: true })
        onRecord({
          ...structure,
          rootNodeId: structure.rootNodeId || nodeId,
          nodes: [...structure.nodes, {
            nodeId,
            x: Math.round(position.x / BLOCK_SCALE),
            y: 0,
            z: Math.round(position.y / BLOCK_SCALE),
          }],
        })
      }}
    >
      <ReactFlow
        nodes={nodes}
        edges={edges}
        nodeTypes={nodeTypes}
        onNodesChange={onNodesChange}
        onEdgesChange={onEdgesChange}
        onNodesDelete={removeNodes}
        onEdgesDelete={removeEdges}
        onNodeDragStart={onBeginTransaction}
        onNodeDragStop={onCommitTransaction}
        onConnect={connect}
        onSelectionChange={selectionChanged}
        isValidConnection={validConnection}
        snapToGrid
        snapGrid={GRID}
        deleteKeyCode={['Backspace', 'Delete']}
        selectionOnDrag
        multiSelectionKeyCode={['Control', 'Meta', 'Shift']}
        panOnDrag={[1, 2]}
        fitView
        minZoom={0.1}
        maxZoom={2.5}
      >
        <Background variant={BackgroundVariant.Dots} gap={20} size={1.4} color="#3c4a61" />
        <MiniMap pannable zoomable nodeColor={miniMapNodeColor} />
        <Controls showInteractive={false} />
      </ReactFlow>
      <div className="canvas-hint">X / Z 相対ブロック座標 · grid 1 block · Shift/Ctrlで複数選択 · Deleteで削除</div>
    </div>
  )
}

function SkillNode({ data, selected }: NodeProps<Node<SkillNodeData>>) {
  return (
    <div className={`skill-node ${selected ? 'selected' : ''} ${data.root ? 'root' : ''}`}>
      <Handle type="target" position={Position.Left} />
      <Handle type="source" position={Position.Right} />
      <Handle type="source" position={Position.Bottom} id="bottom" />
      <Handle type="target" position={Position.Top} id="top" />
      <span className="node-kicker">#{data.nodeId} · Y {data.y}</span>
      <strong>{data.label}</strong>
      {data.root && <span className="root-label">ROOT</span>}
    </div>
  )
}

const edgeId = (source: string | null, target: string | null) => {
  const first = (source ?? '') < (target ?? '') ? source : target
  const second = (source ?? '') < (target ?? '') ? target : source
  return `edge:${first ?? ''}:${second ?? ''}`
}

const miniMapNodeColor = (node: Node) => node.data.root ? '#e0b56a' : '#6d86ad'
