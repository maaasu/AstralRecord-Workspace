import { useCallback, useEffect, useMemo, useRef, useState, type CSSProperties, type MouseEvent as ReactMouseEvent } from 'react'
import {
  Background,
  BackgroundVariant,
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
import type { NodeMaster, SkillMasterSummary, StructureDocument } from '../types/editor'
import { MinecraftIcon } from './MinecraftIcon'
import { stripMinecraftFormatting } from '../utils/minecraft'
import { describeNodeEffects, nodeTooltip, type NodeEffectPresentation } from '../data/nodeEffectPresentation'

interface SkillTreeCanvasProps {
  structure: StructureDocument
  masters: NodeMaster[]
  onRecord: (structure: StructureDocument) => void
  onReplace: (structure: StructureDocument) => void
  onBeginTransaction: () => void
  onCommitTransaction: () => void
  onSelectedNode: (nodeId: string | null) => void
  onEditMaster: (node: NodeMaster) => void
  onNotify: (message: string) => void
  iconRevision?: number
  visibleNodeIds?: ReadonlySet<string> | null
  skillMasters?: readonly SkillMasterSummary[]
  nodeSize?: number
}

interface SkillNodeData extends Record<string, unknown> {
  label: string
  nodeId: string
  y: number
  root: boolean
  icon: NodeMaster['icon']
  iconRevision: number
  pointCost: number
  pointType: string
  effects: NodeEffectPresentation[]
  nodeSize: number
}

interface NodeContextMenuState {
  nodeId: string
  targetIds: string[]
  x: number
  y: number
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
  onEditMaster,
  onNotify,
  iconRevision = 0,
  visibleNodeIds = null,
  skillMasters = [],
  nodeSize = 56,
}: SkillTreeCanvasProps) {
  const { screenToFlowPosition } = useReactFlow()
  const canvasRef = useRef<HTMLDivElement>(null)
  const [selectedIds, setSelectedIds] = useState<Set<string>>(() => new Set())
  const [selectedEdgeIds, setSelectedEdgeIds] = useState<Set<string>>(() => new Set())
  const [contextMenu, setContextMenu] = useState<NodeContextMenuState | null>(null)
  const masterMap = useMemo(() => new Map(masters.map((node) => [node.nodeId, node])), [masters])
  const nodes = useMemo<Node<SkillNodeData>[]>(() => structure.nodes
    .filter((placement) => !visibleNodeIds || visibleNodeIds.has(placement.nodeId))
    .map((placement) => {
    const master = masterMap.get(placement.nodeId)
    return {
      id: placement.nodeId,
      type: 'skill',
      selected: selectedIds.has(placement.nodeId),
      position: { x: placement.x * BLOCK_SCALE, y: placement.z * BLOCK_SCALE },
      data: {
        label: master ? stripMinecraftFormatting(master.name) : `Unknown ${placement.nodeId}`,
        nodeId: placement.nodeId,
        y: placement.y,
        root: placement.nodeId === structure.rootNodeId,
        icon: master?.icon ?? '',
        iconRevision,
        pointCost: master?.pointCost ?? 0,
        pointType: master?.pointType ?? '',
        effects: master ? describeNodeEffects(master, skillMasters) : [],
        nodeSize,
      },
    }
    }),
    [iconRevision, masterMap, nodeSize, selectedIds, skillMasters, structure.nodes, structure.rootNodeId, visibleNodeIds],
  )
  const edges = useMemo<Edge[]>(() => structure.edges
    .filter((edge) => !visibleNodeIds
      || visibleNodeIds.has(edge.sourceNodeId) && visibleNodeIds.has(edge.targetNodeId))
    .map((edge) => ({
      id: edgeId(edge.sourceNodeId, edge.targetNodeId),
      source: edge.sourceNodeId,
      target: edge.targetNodeId,
      type: 'straight',
      className: 'skill-edge',
      selected: selectedEdgeIds.has(edgeId(edge.sourceNodeId, edge.targetNodeId)),
    })),
    [selectedEdgeIds, structure.edges, visibleNodeIds],
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

  const removeNodeIds = useCallback((ids: Set<string>) => {
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

  const removeNodes = useCallback((deleted: Node[]) => {
    removeNodeIds(new Set(deleted.map((node) => node.id)))
  }, [removeNodeIds])

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

  useEffect(() => {
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setContextMenu(null)
    }
    window.addEventListener('keydown', closeOnEscape)
    return () => window.removeEventListener('keydown', closeOnEscape)
  }, [])

  const openNodeContextMenu = useCallback((event: ReactMouseEvent, node: Node) => {
    event.preventDefault()
    const selectedTargets = selectedIds.has(node.id) ? [...selectedIds] : [node.id]
    if (!selectedIds.has(node.id)) setSelectedIds(new Set([node.id]))
    setSelectedEdgeIds(new Set())
    onSelectedNode(node.id)

    const bounds = canvasRef.current?.getBoundingClientRect()
    const localX = event.clientX - (bounds?.left ?? 0)
    const localY = event.clientY - (bounds?.top ?? 0)
    const availableWidth = bounds?.width ?? 0
    const availableHeight = bounds?.height ?? 0
    setContextMenu({
      nodeId: node.id,
      targetIds: selectedTargets,
      x: clampMenuPosition(localX, availableWidth, 230),
      y: clampMenuPosition(localY, availableHeight, 250),
    })
  }, [onSelectedNode, selectedIds])

  const deleteConnections = useCallback((nodeId: string) => {
    onRecord({
      ...structure,
      edges: structure.edges.filter((edge) => edge.sourceNodeId !== nodeId && edge.targetNodeId !== nodeId),
    })
    setContextMenu(null)
  }, [onRecord, structure])

  const copyNodeId = useCallback(async (nodeId: string) => {
    try {
      await navigator.clipboard.writeText(nodeId)
      onNotify(`nodeId '${nodeId}' をコピーしました。`)
    } catch {
      onNotify(`nodeId: ${nodeId}（クリップボードへコピーできませんでした）`)
    } finally {
      setContextMenu(null)
    }
  }, [onNotify])

  const contextMaster = contextMenu ? masterMap.get(contextMenu.nodeId) : undefined
  const contextHasEdges = contextMenu
    ? structure.edges.some((edge) => edge.sourceNodeId === contextMenu.nodeId || edge.targetNodeId === contextMenu.nodeId)
    : false

  return (
    <div
      className="canvas"
      ref={canvasRef}
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
        onNodeDragStart={() => {
          setContextMenu(null)
          onBeginTransaction()
        }}
        onNodeDragStop={onCommitTransaction}
        onConnect={connect}
        onSelectionChange={selectionChanged}
        onNodeContextMenu={openNodeContextMenu}
        onPaneClick={() => setContextMenu(null)}
        onMoveStart={() => setContextMenu(null)}
        isValidConnection={validConnection}
        snapToGrid
        snapGrid={GRID}
        deleteKeyCode={['Backspace', 'Delete']}
        selectionOnDrag={false}
        selectionKeyCode="Shift"
        multiSelectionKeyCode={['Control', 'Meta', 'Shift']}
        panOnDrag={[0, 1, 2]}
        fitView
        minZoom={0.1}
        maxZoom={2.5}
      >
        <Background variant={BackgroundVariant.Dots} gap={20} size={1.4} color="#3c4a61" />
        <MiniMap pannable zoomable nodeColor={miniMapNodeColor} />
      </ReactFlow>
      {contextMenu && (
        <div
          className="node-context-menu"
          role="menu"
          aria-label={`ノード #${contextMenu.nodeId} の操作`}
          style={{ left: contextMenu.x, top: contextMenu.y }}
          onContextMenu={(event) => event.preventDefault()}
        >
          <div className="context-menu-heading">
            <strong>#{contextMenu.nodeId}</strong>
            {contextMenu.targetIds.length > 1 && <span>{contextMenu.targetIds.length}件選択中</span>}
          </div>
          <button role="menuitem" disabled={!contextMaster} onClick={() => {
            if (contextMaster) onEditMaster(contextMaster)
            setContextMenu(null)
          }}>マスター定義を編集</button>
          <button role="menuitem" disabled={structure.rootNodeId === contextMenu.nodeId} onClick={() => {
            onRecord({ ...structure, rootNodeId: contextMenu.nodeId })
            setContextMenu(null)
          }}>ROOTに設定</button>
          <button role="menuitem" onClick={() => void copyNodeId(contextMenu.nodeId)}>nodeIdをコピー</button>
          <button role="menuitem" disabled={!contextHasEdges} onClick={() => deleteConnections(contextMenu.nodeId)}>このノードの接続をすべて削除</button>
          <button role="menuitem" className="danger" onClick={() => {
            removeNodeIds(new Set(contextMenu.targetIds))
            setContextMenu(null)
          }}>
            {contextMenu.targetIds.length > 1 ? `選択中${contextMenu.targetIds.length}件を配置から削除` : '配置から削除'}
          </button>
        </div>
      )}
      <div className="canvas-hint">空白ドラッグで移動 · Shift＋空白ドラッグで範囲選択 · Ctrl/Cmdで追加選択 · 右クリックでノード操作</div>
    </div>
  )
}

function SkillNode({ data, selected }: NodeProps<Node<SkillNodeData>>) {
  const compact = data.nodeSize < 72
  const tooltipNode = {
    $schema: '', schemaVersion: 1, nodeId: data.nodeId, name: data.label, icon: data.icon,
    lore: [], tags: [], pointType: data.pointType, pointCost: data.pointCost, effects: [],
  } satisfies NodeMaster
  return (
    <div
      className={`skill-node ${selected ? 'selected' : ''} ${data.root ? 'root' : ''} ${compact ? 'compact' : ''}`}
      style={{ '--skill-node-size': `${data.nodeSize}px` } as CSSProperties}
      title={nodeTooltip(tooltipNode, data.effects)}
    >
      <Handle type="target" position={Position.Left} />
      <Handle type="source" position={Position.Right} />
      <Handle type="source" position={Position.Bottom} id="bottom" />
      <Handle type="target" position={Position.Top} id="top" />
      <strong title={data.label}>{data.label}</strong>
      <MinecraftIcon icon={data.icon} revision={data.iconRevision} className="canvas-node-icon" />
      <span className="node-kicker">#{data.nodeId} · {data.pointType} {data.pointCost} · Y {data.y}</span>
      {data.effects.length > 0 && <span className="effect-count" aria-label={`効果 ${data.effects.length}件`}>{data.effects.length}</span>}
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

const clampMenuPosition = (position: number, available: number, menuSize: number) => (
  Math.max(8, Math.min(position, Math.max(8, available - menuSize - 8)))
)
