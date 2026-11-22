import { usePanelDocument } from '../docking/dockPanel'
import { memo, useCallback, useEffect, useMemo, useRef, useState, type CSSProperties, type MouseEvent as ReactMouseEvent } from 'react'
import {
  BaseEdge,
  ConnectionLineType,
  applyNodeChanges,
  getViewportForBounds,
  Handle,
  MiniMap,
  Panel,
  Position,
  ReactFlow,
  ReactFlowProvider,
  useReactFlow,
  useStore,
  useStoreApi,
  type Connection,
  type Edge,
  type EdgeChange,
  type EdgeProps,
  type MiniMapNodeProps,
  type Node,
  type NodeChange,
  type NodeHandle,
  type NodeProps,
  type ReactFlowState,
} from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import type { ClassMasterSummary, NodeMaster, SkillMasterSummary, StructureDocument } from '../types/editor'
import { MinecraftIcon } from './MinecraftIcon'
import { stripMinecraftFormatting } from '../utils/minecraft'
import { nodeTooltip } from '../data/nodeEffectPresentation'
import { BLOCK_SCALE, DEFAULT_NODE_SIZE, LABEL_GAP, LABEL_HALF_WIDTH, LABEL_HEIGHT, clippedEdgePath, measureLabelZoom, measureNodeSpacing, nodeDiameter } from '../state/skillTreeMapLayout'
import { MapEffectList, NODE_KIND_LABEL, mapNodeCost, mapNodeEffects, nodeClassName, nodeKind, nodeLevel, pointType, shortNodeName, type MapEffect, type NodeKind } from './skillTreeMapPresentation'
import { MinecraftText } from './MinecraftText'
import { masterTagTooltip } from '../data/masterTagPresentation'

/** 一覧からキャンバスへ渡す、1回分の移動要求。 */
export interface CanvasFocusRequest {
  nodeId: string
}

interface SkillTreeCanvasProps {
  structure: StructureDocument
  masters: NodeMaster[]
  onRecord: (structure: StructureDocument) => boolean | void
  onReplace: (structure: StructureDocument) => void
  onBeginTransaction: () => void
  onCommitTransaction: () => void
  onSelectedNode: (nodeId: string | null) => boolean | void
  onEditMaster: (node: NodeMaster) => void
  onNotify: (message: string) => void
  iconRevision?: number
  matchingNodeIds?: ReadonlySet<string> | null
  visibleNodeIds?: ReadonlySet<string> | null
  skillMasters?: readonly SkillMasterSummary[]
  classMasters?: readonly ClassMasterSummary[]
  nodeSize?: number
  selectedNodeId?: string | null
  focusRequest?: CanvasFocusRequest | null
  onFocusHandled?: (request: CanvasFocusRequest) => void
  learnedSelection?: { nodeIds: ReadonlySet<string>; disabled?: boolean; onToggle: (nodeId: string) => void }
  androidGestures?: boolean
}

/** マスター定義と一覧だけで決まる表示内容。配置や選択が変わっても作り直さない。 */
interface NodePresentation {
  label: string
  icon: NodeMaster['icon']
  name: string
  shortName: string
  master?: NodeMaster
  pointType: 'PP' | 'CP'
  className: string
  level?: number
  effects: MapEffect[]
  costTitle: string
  title: string
}

interface SkillNodeData extends Record<string, unknown> {
  presentation: NodePresentation
  nodeId: string
  y: number
  root: boolean
  iconRevision: number
  nodeSize: number
  labelAtZoom: number
  coincidentIds: readonly string[]
  overlapOffset?: number
  kind: NodeKind
  learned?: boolean
  selectionDisabled?: boolean
  onToggleLearned?: (nodeId: string) => void
}

type SkillFlowNode = Node<SkillNodeData>

interface NodeDescriptor {
  id: string
  x: number
  y: number
  level: number
  root: boolean
  presentation: NodePresentation
  diameter: number
  labelAtZoom: number
  coincidentIds: readonly string[]
  overlapOffset?: number
  kind: NodeKind
  learned?: boolean
  selectionDisabled?: boolean
  onToggleLearned?: (nodeId: string) => void
}

interface EdgeTopology {
  id: string
  first: string
  second: string
}

interface CachedEdge {
  first?: SkillFlowNode
  second?: SkillFlowNode
  selected: boolean
  connected: boolean
  edge: Edge
}

interface NodeContextMenuState {
  nodeId: string
  targetIds: string[]
  x: number
  y: number
}

interface NodeDrag {
  pointerStart: { x: number; y: number }
  positions: Map<string, { x: number; z: number }>
  delta: { x: number; z: number }
  changed: boolean
}

const NODE_ORIGIN: [number, number] = [0.5, 0.5]
const GRID_STORAGE_KEY = 'astralrecord.skilltree-editor.grid'
const GRID: [number, number] = [BLOCK_SCALE / 10, BLOCK_SCALE / 10]
const EMPTY_SKILL_MASTERS: readonly SkillMasterSummary[] = []
const EMPTY_CLASS_MASTERS: readonly ClassMasterSummary[] = []
const HANDLE_SIZE = 8
/** この件数までは全体を収めて全ノードを描画する。超える構造は起点を中心に開き、画面外の描画を省く。 */
const SMALL_TREE_NODE_LIMIT = 150
const FOCUS_ZOOM = 0.9
/** これより縮小した表示では文字と影を省き、全体の形を見やすくする。 */
const OVERVIEW_ZOOM = 0.45
const DEFAULT_MIN_ZOOM = 0.1
const MAX_ZOOM = 2.5
const VIEW_ANIMATION_MS = 220
const TOUCH_TAP_MAX_DISTANCE = 9
const DELETE_KEYS = ['Backspace', 'Delete']
const LABEL_STYLE = {
  '--tree-label-gap': `${LABEL_GAP}px`,
  '--tree-label-width': `${LABEL_HALF_WIDTH * 2}px`,
  '--tree-label-height': `${LABEL_HEIGHT}px`,
} as CSSProperties

export function SkillTreeCanvas(props: SkillTreeCanvasProps) {
  return (
    <ReactFlowProvider nodeOrigin={NODE_ORIGIN}>
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
  matchingNodeIds = null,
  skillMasters = EMPTY_SKILL_MASTERS,
  classMasters = EMPTY_CLASS_MASTERS,
  nodeSize = DEFAULT_NODE_SIZE,
  selectedNodeId = null,
  focusRequest = null,
  onFocusHandled,
  learnedSelection,
  androidGestures = false,
}: SkillTreeCanvasProps) {
  const { screenToFlowPosition, setViewport, zoomIn, zoomOut, getZoom, getViewport, getNodes, getNodesBounds, getEdges, deleteElements, viewportInitialized } = useReactFlow<SkillFlowNode>()
  const canvasRef = useRef<HTMLDivElement>(null)
  const touch = useRef({ x: 0, y: 0, interrupted: false })
  const pressedAt = useRef<{ x: number; y: number } | null>(null)
  const drag = useRef<NodeDrag | null>(null)
  const initializedView = useRef(false)
  const appliedVisibility = useRef(visibleNodeIds)
  const handledFocusRequest = useRef<CanvasFocusRequest | null>(null)
  const acceptedSelection = useRef<{ nodes: string[]; edges: string[] }>({ nodes: [], edges: [] })
  const [selectedEdgeIds, setSelectedEdgeIds] = useState<Set<string>>(() => new Set())
  const [contextMenu, setContextMenu] = useState<NodeContextMenuState | null>(null)
  const [hoveredId, setHoveredId] = useState<string | null>(null)
  const [showGrid, setShowGrid] = useState(readGridPreference)
  const toggleGrid = () => {
    const next = !showGrid
    setShowGrid(next)
    try { window.localStorage.setItem(GRID_STORAGE_KEY, String(next)) } catch { /* 表示中の切替は維持する。 */ }
  }
  const masterMap = useMemo(() => new Map(masters.map((node) => [node.nodeId, node])), [masters])
  // スキル・職業の一覧が変わるまで、マスター1件につき説明文を1回だけ組み立てる。
  const presentations = useMemo(() => {
    const known = new WeakMap<NodeMaster, NodePresentation>()
    const unknown = new Map<string, NodePresentation>()
    return {
      forNode(nodeId: string, master: NodeMaster | undefined): NodePresentation {
        let presentation = master ? known.get(master) : unknown.get(nodeId)
        if (presentation) return presentation
        presentation = master ? describeMaster(master, skillMasters, classMasters) : describeUnknown(nodeId)
        if (master) known.set(master, presentation)
        else unknown.set(nodeId, presentation)
        return presentation
      },
    }
  }, [classMasters, skillMasters])
  const visiblePlacements = useMemo(() => structure.nodes.filter(
    placement => !visibleNodeIds || visibleNodeIds.has(placement.nodeId),
  ), [structure.nodes, visibleNodeIds])
  // 履歴は構造全体を複製する。空間計算は、地図上の位置や表示対象が変わった場合だけ行う。
  const positionsKey = useMemo(() => JSON.stringify(visiblePlacements
    .map(({ nodeId, x, z }) => ({ nodeId, x, z }))
    .sort((a, b) => a.nodeId.localeCompare(b.nodeId))), [visiblePlacements])
  const mapPlacements = useMemo(() => JSON.parse(positionsKey) as Pick<StructureDocument['nodes'][number], 'nodeId' | 'x' | 'z'>[], [positionsKey])
  const spacing = useMemo(() => measureNodeSpacing(mapPlacements), [mapPlacements])
  const geometry = useMemo(() => {
    const overlapOffsets = new Map<readonly string[], number>()
    const frames = mapPlacements.map(({ nodeId, x, z }) => {
      const master = masterMap.get(nodeId)
      const scale = master?.tags?.includes('keystone') ? 1.35 : master?.tags?.includes('notable') ? 1.2 : 1
      const localSpacing = spacing.get(nodeId)!
      const diameter = nodeDiameter(nodeSize, localSpacing, scale, pointType(master) === 'CP')
      const ids = localSpacing.coincidentIds
      if (ids.length > 1) overlapOffsets.set(ids, Math.max(overlapOffsets.get(ids) ?? 0, diameter / 2))
      return { id: nodeId, x: x * BLOCK_SCALE, y: z * BLOCK_SCALE, diameter }
    })
    const labelZoom = measureLabelZoom(frames, spacing, OVERVIEW_ZOOM, MAX_ZOOM)
    return new Map(frames.map(frame => [frame.id, {
      ...frame,
      labelAtZoom: labelZoom.get(frame.id)!,
      overlapOffset: overlapOffsets.get(spacing.get(frame.id)!.coincidentIds),
    }]))
  }, [mapPlacements, masterMap, nodeSize, spacing])
  const descriptors = useMemo<NodeDescriptor[]>(() => visiblePlacements.map((placement) => {
    const root = placement.nodeId === structure.rootNodeId
    const master = masterMap.get(placement.nodeId)
    const kind = nodeKind(master, root)
    return {
      ...geometry.get(placement.nodeId)!,
      level: placement.y,
      root,
      kind,
      coincidentIds: spacing.get(placement.nodeId)!.coincidentIds,
      presentation: presentations.forNode(placement.nodeId, master),
      learned: learnedSelection ? learnedSelection.nodeIds.has(placement.nodeId) : undefined,
      selectionDisabled: learnedSelection?.disabled,
      onToggleLearned: learnedSelection?.onToggle,
    }
  }), [geometry, masterMap, presentations, structure.rootNodeId, visiblePlacements, spacing, learnedSelection?.nodeIds, learnedSelection?.disabled, learnedSelection?.onToggle])

  // 配置の中心と計測済みの寸法を引き継ぎ、変更があるノードだけ置き換える。
  const [flowNodes, setFlowNodes] = useState<SkillFlowNode[]>(() => reconcileNodes([], descriptors, iconRevision, selectedNodeId))
  const [syncedInputs, setSyncedInputs] = useState({ descriptors, iconRevision })
  if (syncedInputs.descriptors !== descriptors || syncedInputs.iconRevision !== iconRevision) {
    setSyncedInputs({ descriptors, iconRevision })
    setFlowNodes(reconcileNodes(flowNodes, descriptors, iconRevision, selectedNodeId))
  }

  const topology = useMemo<EdgeTopology[]>(() => structure.edges
    .filter((edge) => !visibleNodeIds
      || visibleNodeIds.has(edge.sourceNodeId) && visibleNodeIds.has(edge.targetNodeId))
    .map((edge) => {
      const first = edge.sourceNodeId
      const second = edge.targetNodeId
      return { id: edgeId(first, second), first, second }
    }),
    [structure.edges, visibleNodeIds],
  )
  const flowNodeMap = useMemo(() => new Map(flowNodes.map(node => [node.id, node])), [flowNodes])
  const neighborsByNode = useMemo(() => {
    const neighbors = new Map<string, Set<string>>()
    for (const { first, second } of topology) {
      if (!neighbors.has(first)) neighbors.set(first, new Set())
      if (!neighbors.has(second)) neighbors.set(second, new Set())
      neighbors.get(first)!.add(second)
      neighbors.get(second)!.add(first)
    }
    return neighbors
  }, [topology])
  const emphasizedIds = useMemo(() => {
    const ids = new Set(flowNodes.filter(node => node.selected).map(node => node.id))
    if (hoveredId) ids.add(hoveredId)
    return ids
  }, [flowNodes, hoveredId])
  const styledNodes = useMemo(() => new WeakMap<SkillFlowNode, SkillFlowNode>(), [])
  const mapNodes = useMemo(() => {
    const neighboring = new Set<string>()
    for (const id of emphasizedIds) {
      for (const neighbor of neighborsByNode.get(id) ?? []) neighboring.add(neighbor)
    }
    if (!neighboring.size && !matchingNodeIds) return flowNodes
    return flowNodes.map(node => {
      const neighbor = neighboring.has(node.id) ? 'tree-neighbor' : ''
      const match = matchingNodeIds ? matchingNodeIds.has(node.id) ? 'tree-match' : 'tree-dim' : ''
      if (!neighbor && !match) return node
      const className = [neighbor, match].filter(Boolean).join(' ')
      const cached = styledNodes.get(node)
      if (cached?.className === className) return cached
      const styled = { ...node, className }
      styledNodes.set(node, styled)
      return styled
    })
  }, [emphasizedIds, flowNodes, matchingNodeIds, neighborsByNode, styledNodes])

  const edgeCache = useMemo(() => new Map<string, CachedEdge>(), [])
  const edges = useMemo<Edge[]>(() => {
    const next = topology.map(({ id, first, second }) => {
      const firstNode = flowNodeMap.get(first)
      const secondNode = flowNodeMap.get(second)
      const selected = selectedEdgeIds.has(id)
      const connected = emphasizedIds.has(first) || emphasizedIds.has(second)
      const cached = edgeCache.get(id)
      if (cached && cached.selected === selected && cached.connected === connected
        && sameEdgeEnd(cached.first, firstNode) && sameEdgeEnd(cached.second, secondNode)) return cached.edge
      const edge: Edge = {
        id,
        ...routeBetween(first, second, firstNode?.position, secondNode?.position),
        type: 'tree',
        data: { path: firstNode && secondNode ? clippedEdgePath(edgeEnd(firstNode), edgeEnd(secondNode)) : '' },
        className: connected ? 'skill-edge connected' : 'skill-edge',
        selected,
      }
      edgeCache.set(id, { first: firstNode, second: secondNode, selected, connected, edge })
      return edge
    })
    if (edgeCache.size > next.length) {
      const live = new Set(next.map((edge) => edge.id))
      for (const id of edgeCache.keys()) if (!live.has(id)) edgeCache.delete(id)
    }
    return next
  }, [edgeCache, emphasizedIds, flowNodeMap, selectedEdgeIds, topology])

  const largeTree = descriptors.length > SMALL_TREE_NODE_LIMIT
  const visibleRoot = useMemo(() => descriptors.find((node) => node.root), [descriptors])
  const [overviewMinZoom, setOverviewMinZoom] = useState(DEFAULT_MIN_ZOOM)

  const onNodesChange = useCallback((changes: NodeChange<SkillFlowNode>[]) => {
    // 削除は構造の更新が承認された場合にだけ、配置データの変更として反映する。
    const session = drag.current
    const localChanges = changes.filter(change => change.type !== 'remove').map(change =>
      change.type === 'position' && session?.positions.has(change.id)
        ? { ...change, position: undefined }
        : change,
    )
    if (localChanges.length) setFlowNodes(current => applyNodeChanges<SkillFlowNode>(localChanges, current))
  }, [])

  const pointerPosition = useCallback((pointer: { clientX: number; clientY: number }) =>
    screenToFlowPosition({ x: pointer.clientX, y: pointer.clientY }, { snapToGrid: false }),
  [screenToFlowPosition])

  const updateDraggedNodes = useCallback((event: MouseEvent | TouchEvent) => {
    const session = drag.current
    const pointer = 'changedTouches' in event ? event.changedTouches[0] : event
    if (!session || !pointer) return
    const position = pointerPosition(pointer)
    const delta = {
      x: Math.round((position.x - session.pointerStart.x) * 10 / BLOCK_SCALE),
      z: Math.round((position.y - session.pointerStart.y) * 10 / BLOCK_SCALE),
    }
    if (delta.x === session.delta.x && delta.z === session.delta.z) return
    session.delta = delta
    if (!session.changed) {
      session.changed = true
      setContextMenu(null)
      onBeginTransaction()
    }
    setFlowNodes(current => current.map(node => {
      const start = session.positions.get(node.id)
      if (!start) return node
      const { x, z } = draggedPosition(start, delta)
      return { ...node, position: { x: x * BLOCK_SCALE, y: z * BLOCK_SCALE } }
    }))
  }, [onBeginTransaction, pointerPosition])

  const commitDraggedNodes = useCallback(() => {
    const session = drag.current
    drag.current = null
    if (!session?.changed) return
    if (session.delta.x || session.delta.z) {
      onReplace({
        ...structure,
        nodes: structure.nodes.map(placement => {
          const start = session.positions.get(placement.nodeId)
          return start ? { ...placement, ...draggedPosition(start, session.delta) } : placement
        }),
      })
    }
    onCommitTransaction()
  }, [onCommitTransaction, onReplace, structure])

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

  const removeElements = useCallback((ids: Set<string>, edgeIds: Set<string> = new Set()) => {
    if (!ids.size && !edgeIds.size) return
    const nextEdges = structure.edges.filter((edge) => !ids.has(edge.sourceNodeId) && !ids.has(edge.targetNodeId)
      && !edgeIds.has(edgeId(edge.sourceNodeId, edge.targetNodeId)))
    if (onRecord({
      ...structure,
      rootNodeId: ids.has(structure.rootNodeId) ? '' : structure.rootNodeId,
      nodes: ids.size ? structure.nodes.filter((node) => !ids.has(node.nodeId)) : structure.nodes,
      edges: nextEdges,
    }) === false) return

    setSelectedEdgeIds((current) => {
      if (!current.size) return current
      const remaining = new Set(nextEdges.map((edge) => edgeId(edge.sourceNodeId, edge.targetNodeId)))
      return new Set([...current].filter((id) => remaining.has(id)))
    })
  }, [onRecord, structure])

  const selectionChanged = useCallback(({ nodes, edges }: { nodes: Node[]; edges: Edge[] }) => {
    const previous = acceptedSelection.current
    const nodeIds = nodes.map((node) => node.id)
    const edgeIds = edges.map((edge) => edge.id)
    if (nodeIds[0] !== previous.nodes[0] && onSelectedNode(nodeIds[0] ?? null) === false) {
      setFlowNodes((current) => selectNodes(current, new Set(previous.nodes)))
      setSelectedEdgeIds(new Set(previous.edges))
      return
    }
    acceptedSelection.current = { nodes: nodeIds, edges: edgeIds }
  }, [onSelectedNode])

  const validConnection = useCallback((connection: Edge | Connection) => Boolean(
    connection.source
    && connection.target
    && connection.source !== connection.target
    && !structure.edges.some((edge) => edgeId(edge.sourceNodeId, edge.targetNodeId) === edgeId(connection.source, connection.target)),
  ), [structure.edges])

  const window = usePanelDocument().defaultView!
  useEffect(() => {
    const closeOnEscape = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setContextMenu(null)
    }
    window.addEventListener('keydown', closeOnEscape)
    return () => window.removeEventListener('keydown', closeOnEscape)
  }, [window])

  const openNodeContextMenu = useCallback((event: ReactMouseEvent, node: Node) => {
    event.preventDefault()
    const selected = getNodes().filter((entry) => entry.selected).map((entry) => entry.id)
    const alreadySelected = selected.includes(node.id)
    if (!alreadySelected) {
      if (onSelectedNode(node.id) === false) return
      setFlowNodes((current) => selectNodes(current, new Set([node.id])))
    }
    setSelectedEdgeIds((current) => current.size ? new Set() : current)

    const bounds = canvasRef.current?.getBoundingClientRect()
    const localX = event.clientX - (bounds?.left ?? 0)
    const localY = event.clientY - (bounds?.top ?? 0)
    const availableWidth = bounds?.width ?? 0
    const availableHeight = bounds?.height ?? 0
    setContextMenu({
      nodeId: node.id,
      targetIds: alreadySelected ? selected : [node.id],
      x: clampMenuPosition(localX, availableWidth, 230),
      y: clampMenuPosition(localY, availableHeight, 250),
    })
  }, [getNodes, onSelectedNode])

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

  const centerOnNode = useCallback((node: { x: number; y: number }, animate: boolean) => {
    const bounds = canvasRef.current?.getBoundingClientRect()
    if (!bounds?.width || !bounds.height) return false
    const zoom = animate ? Math.max(getZoom(), FOCUS_ZOOM) : FOCUS_ZOOM
    void setViewport(
      { x: bounds.width / 2 - node.x * zoom, y: bounds.height / 2 - node.y * zoom, zoom },
      { duration: animate ? VIEW_ANIMATION_MS : 0 },
    )
    return true
  }, [getZoom, setViewport])

  const showSelection = useCallback(() => {
    const selected = getNodes().find((node) => node.selected)
    if (selected) centerOnNode(selected.position, true)
  }, [centerOnNode, getNodes])

  const fitNodes = useCallback((rect: { width: number; height: number }, animate: boolean) => {
    const nodes = getNodes()
    if (!nodes.length || !rect.width || !rect.height) return
    const viewport = getViewportForBounds(getNodesBounds(nodes), rect.width, rect.height, 0, MAX_ZOOM, 0.1)
    setOverviewMinZoom((current) => Math.min(current, viewport.zoom))
    // 大規模な構造では、中間倍率ごとに画面へ入るノードを描画する処理を省く。
    void setViewport(viewport, { duration: animate && !largeTree ? VIEW_ANIMATION_MS : 0 })
  }, [getNodes, getNodesBounds, largeTree, setViewport])

  const showAll = useCallback(() => {
    const bounds = canvasRef.current?.getBoundingClientRect()
    if (bounds) fitNodes(bounds, true)
  }, [fitNodes])

  useEffect(() => {
    const canvas = canvasRef.current
    if (!viewportInitialized || !canvas) return
    if (!focusRequest && initializedView.current && appliedVisibility.current === visibleNodeIds) return

    // 狭幅で他のペインを開いている間は、キャンバスに実際の幅が入るまで待つ。
    const updateView = () => {
      const bounds = canvas.getBoundingClientRect()
      if (!bounds.width || !bounds.height) return false
      const filterChanged = appliedVisibility.current !== visibleNodeIds
      appliedVisibility.current = visibleNodeIds
      if (focusRequest && handledFocusRequest.current !== focusRequest) {
        handledFocusRequest.current = focusRequest
        const target = descriptors.find((node) => node.id === focusRequest.nodeId)
        if (target && onSelectedNode(target.id) !== false && centerOnNode(target, initializedView.current)) {
          initializedView.current = true
          setContextMenu(null)
          setFlowNodes((current) => selectNodes(current, new Set([target.id])))
          setSelectedEdgeIds((current) => current.size ? new Set() : current)
          onFocusHandled?.(focusRequest)
          return true
        }
        if (!target) {
          onNotify(`ノード #${focusRequest.nodeId} は現在の表示条件ではキャンバスに表示されていません。`)
        }
        onFocusHandled?.(focusRequest)
      }
      if (!descriptors.length) return true
      const viewport = getViewport()
      const entry = visibleRoot ?? descriptors[0]
      if (!initializedView.current) {
        initializedView.current = true
        const selected = descriptors.find((node) => node.id === selectedNodeId)
        if (largeTree) centerOnNode(selected ?? entry, false)
        else fitNodes(bounds, false)
      } else if (filterChanged && !descriptors.some((node) => isNodeInView(node, bounds, viewport))) {
        if (largeTree) centerOnNode(entry, false)
        else fitNodes(bounds, false)
      }
      return true
    }
    if (updateView()) return
    const observer = new ResizeObserver(() => { if (updateView()) observer.disconnect() })
    observer.observe(canvas)
    return () => observer.disconnect()
  }, [centerOnNode, descriptors, fitNodes, focusRequest, getViewport, largeTree, onFocusHandled, onNotify, onSelectedNode, selectedNodeId, viewportInitialized, visibleNodeIds, visibleRoot])

  const contextMaster = contextMenu ? masterMap.get(contextMenu.nodeId) : undefined
  const contextHasEdges = contextMenu
    ? structure.edges.some((edge) => edge.sourceNodeId === contextMenu.nodeId || edge.targetNodeId === contextMenu.nodeId)
    : false
  const hoveredNode = hoveredId ? flowNodeMap.get(hoveredId) : undefined
  const hasSelectedNode = useMemo(() => flowNodes.some((node) => node.selected), [flowNodes])

  return (
    <div
      className="canvas tree-map"
      ref={canvasRef}
      style={LABEL_STYLE}
      onMouseDownCapture={learnedSelection ? undefined : event => {
        if (event.button === 0) pressedAt.current = pointerPosition(event)
      }}
      onPointerDownCapture={androidGestures ? event => {
        if (event.pointerType !== 'touch') touch.current.interrupted = false
      } : undefined}
      onTouchStartCapture={event => {
        const first = event.touches[0]
        if (!first) return
        if (!learnedSelection && event.touches.length === 1) pressedAt.current = pointerPosition(first)
        if (androidGestures) touch.current = { x: first.clientX, y: first.clientY, interrupted: event.touches.length > 1 }
      }}
      onTouchMoveCapture={androidGestures ? event => {
        const first = event.touches[0]
        const { x, y } = touch.current
        if (event.touches.length > 1 || Math.hypot(first.clientX - x, first.clientY - y) > TOUCH_TAP_MAX_DISTANCE) {
          touch.current.interrupted = true
        }
      } : undefined}
      onTouchCancelCapture={androidGestures ? () => { touch.current.interrupted = true } : undefined}
      onKeyDown={(event) => {
        if (learnedSelection) return
        if (!DELETE_KEYS.includes(event.key) || event.defaultPrevented || event.repeat
          || event.ctrlKey || event.metaKey || event.altKey || event.shiftKey || event.nativeEvent.isComposing) return
        const target = event.target as Element
        if (target.closest('input, textarea, select, [contenteditable]')) return
        event.preventDefault()
        void deleteElements({
          nodes: getNodes().filter(node => node.selected),
          edges: getEdges().filter(edge => edge.selected),
        })
      }}
      onDragOver={(event) => {
        if (learnedSelection) return
        event.preventDefault()
        event.dataTransfer.dropEffect = 'copy'
      }}
      onDrop={(event) => {
        if (learnedSelection) return
        event.preventDefault()
        const nodeId = event.dataTransfer.getData('application/x-astral-node')
        if (!nodeId || structure.nodes.some((node) => node.nodeId === nodeId)) return
        const position = screenToFlowPosition({ x: event.clientX, y: event.clientY }, { snapToGrid: true })
        onRecord({
          ...structure,
          rootNodeId: structure.rootNodeId || nodeId,
          nodes: [...structure.nodes, {
            nodeId,
            x: toTenthBlock(position.x / BLOCK_SCALE),
            y: 0,
            z: toTenthBlock(position.y / BLOCK_SCALE),
          }],
        })
      }}
    >
      <ReactFlow
        nodes={mapNodes}
        edges={edges}
        nodeTypes={nodeTypes}
        edgeTypes={edgeTypes}
        nodeOrigin={NODE_ORIGIN}
        connectionLineType={ConnectionLineType.Straight}
        onNodeMouseEnter={(_, node) => setHoveredId(node.id)}
        onNodeMouseLeave={(_, node) => setHoveredId(current => current === node.id ? null : current)}
        onNodesChange={onNodesChange}
        onEdgesChange={onEdgesChange}
        onDelete={({ nodes, edges }) => removeElements(new Set(nodes.map((node) => node.id)), new Set(edges.map((edge) => edge.id)))}
        onNodeDragStart={(event, _, nodes) => {
          const pointer = 'changedTouches' in event ? event.changedTouches[0] : event
          if (!pointer) return
          const pointerStart = pressedAt.current ?? pointerPosition(pointer)
          pressedAt.current = null
          const ids = new Set(nodes.map(node => node.id))
          const positions = new Map(structure.nodes.filter(node => ids.has(node.nodeId))
            .map(({ nodeId, x, z }) => [nodeId, { x: Math.round(x * 10), z: Math.round(z * 10) }]))
          drag.current = { pointerStart, positions, delta: { x: 0, z: 0 }, changed: false }
          updateDraggedNodes(event)
        }}
        onNodeDrag={updateDraggedNodes}
        onNodeDragStop={event => {
          updateDraggedNodes(event)
          commitDraggedNodes()
        }}
        onConnect={connect}
        onSelectionChange={selectionChanged}
        nodesDraggable={!learnedSelection}
        nodesConnectable={!learnedSelection}
        nodesFocusable={!learnedSelection}
        edgesFocusable={!learnedSelection}
        edgesReconnectable={!learnedSelection}
        onNodeClick={learnedSelection ? (_, node) => {
          if (!learnedSelection.disabled && !touch.current.interrupted) learnedSelection.onToggle(node.id)
        } : undefined}
        onNodeContextMenu={learnedSelection ? event => event.preventDefault() : openNodeContextMenu}
        onNodeDoubleClick={(_, node) => {
          if (learnedSelection) return
          const master = masterMap.get(node.id)
          if (master) onEditMaster(master)
        }}
        onPaneClick={() => setContextMenu(null)}
        onMoveStart={() => { setContextMenu(null); setHoveredId(null) }}
        isValidConnection={validConnection}
        snapToGrid={false}
        snapGrid={GRID}
        // The dock tab strip also uses these keys. Handle them within the canvas.
        deleteKeyCode={null}
        selectionOnDrag={false}
        selectionKeyCode="Shift"
        multiSelectionKeyCode={['Control', 'Meta', 'Shift']}
        panOnDrag={[0, 1, 2]}
        zoomOnDoubleClick={!androidGestures}
        onlyRenderVisibleElements={largeTree}
        minZoom={overviewMinZoom}
        maxZoom={MAX_ZOOM}
      >
        {showGrid && <MapGrid />}
        <MiniMap pannable zoomable nodeClassName={miniMapNodeClass} nodeComponent={MapMiniDot}
          bgColor="var(--tree-map)" maskColor="color-mix(in srgb, var(--tree-map) 40%, transparent)"
          maskStrokeColor="var(--tree-gold)" maskStrokeWidth={1} style={MINIMAP_SIZE} ariaLabel="構造全体のミニマップ" />
        <Panel position="bottom-left" className="canvas-controls">
          <div className="canvas-zoom" role="group" aria-label="表示倍率">
            <button type="button" aria-label="縮小" title="縮小" onClick={() => void zoomOut({ duration: VIEW_ANIMATION_MS })}>−</button>
            <ZoomLevel />
            <button type="button" aria-label="拡大" title="拡大" onClick={() => void zoomIn({ duration: VIEW_ANIMATION_MS })}>＋</button>
          </div>
          <button type="button" aria-pressed={showGrid} onClick={toggleGrid}>グリッド</button>
          <button type="button" title={visibleRoot ? '起点ノードを中央に表示' : '表示中の起点ノードがありません'} disabled={!visibleRoot} onClick={() => { if (visibleRoot) centerOnNode(visibleRoot, true) }}>起点へ</button>
          <button type="button" title="選択中のノードを中央に表示" disabled={!hasSelectedNode} onClick={showSelection}>選択へ</button>
          <button type="button" title="表示中のノード全体が収まるように表示" disabled={!flowNodes.length} onClick={showAll}>全体表示</button>
        </Panel>
      </ReactFlow>
      <MapCoordinates canvasRef={canvasRef} />
      {hoveredNode && <NodeHoverSummary node={hoveredNode} neighbors={neighborsByNode.get(hoveredNode.id)} masters={masterMap} />}
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
          }}>起点に設定</button>
          <button role="menuitem" onClick={() => void copyNodeId(contextMenu.nodeId)}>nodeIdをコピー</button>
          <button role="menuitem" disabled={!contextHasEdges} onClick={() => deleteConnections(contextMenu.nodeId)}>このノードの接続をすべて削除</button>
          <button role="menuitem" className="danger" onClick={() => {
            removeElements(new Set(contextMenu.targetIds))
            setContextMenu(null)
          }}>
            {contextMenu.targetIds.length > 1 ? `選択中${contextMenu.targetIds.length}件を配置から削除` : '配置から削除'}
          </button>
        </div>
      )}
      {learnedSelection && <div className="canvas-hint">{androidGestures
        ? '指で移動・ピンチで拡大縮小・タップで習得／解除'
        : 'ノードをクリックまたは Space / Enter で習得・解除'}</div>}
    </div>
  )
}

/** 倍率の表示だけを分け、ズーム中にキャンバス全体を描画し直さない。 */
function ZoomLevel() {
  const percent = useStore(zoomPercent)
  return <output className="canvas-zoom-level" aria-label="現在の表示倍率">{percent}%</output>
}

const SkillNode = memo(function SkillNode({ data, selected, isConnectable }: NodeProps<SkillFlowNode>) {
  const { presentation } = data
  const labelVisible = useStore(state => state.transform[2] >= data.labelAtZoom)
  const overview = useStore(isOverviewZoom)
  const handleSize = connectionHandleSize(data.nodeSize)
  const handleStyle = { width: handleSize, height: handleSize, minWidth: handleSize, minHeight: handleSize }
  const handleProps = { style: handleStyle, isConnectable, isConnectableStart: isConnectable, isConnectableEnd: isConnectable }
  return (
    <div
      className={`skill-node ${selected ? 'selected' : ''} ${data.root ? 'root' : ''} ${data.learned ? 'learned' : ''}`}
      data-point-type={presentation.pointType}
      data-node-kind={data.kind}
      data-keystone={presentation.master?.tags?.includes('keystone') || undefined}
      data-label-visible={labelVisible}
      data-overview={overview}
      style={{ '--skill-node-size': `${data.nodeSize}px` } as CSSProperties}
      title={presentation.title}
      role={data.onToggleLearned ? 'checkbox' : undefined}
      aria-label={data.onToggleLearned ? presentation.label : undefined}
      aria-checked={data.onToggleLearned ? data.learned === true : undefined}
      aria-disabled={data.onToggleLearned ? data.selectionDisabled === true : undefined}
      tabIndex={data.onToggleLearned ? 0 : undefined}
      onKeyDown={data.onToggleLearned ? event => {
        if (event.key !== ' ' && event.key !== 'Enter') return
        event.preventDefault(); event.stopPropagation()
        if (!data.selectionDisabled && !event.repeat && !event.nativeEvent.isComposing) data.onToggleLearned?.(data.nodeId)
      } : undefined}
    >
      <Handle type="target" position={Position.Left} id="left" {...handleProps} />
      <Handle type="source" position={Position.Right} id="right" {...handleProps} />
      <Handle type="source" position={Position.Bottom} id="bottom" {...handleProps} />
      <Handle type="target" position={Position.Top} id="top" {...handleProps} />
      <span className="tree-node-frame" aria-hidden="true">
        <MinecraftIcon icon={presentation.icon} revision={data.iconRevision} className="canvas-node-icon" />
      </span>
      {data.overlapOffset !== undefined && <span className="tree-node-overlap"
        style={{ bottom: `calc(50% + ${data.overlapOffset}px)` }}>重なり {data.coincidentIds.length}</span>}
      <span className="tree-node-label" aria-hidden="true">
        <strong><MinecraftText value={presentation.shortName} /></strong>
        <small>{presentation.costTitle}</small>
      </span>
      {data.learned && <span className="learned-node-mark" aria-hidden="true">✓</span>}
    </div>
  )
})

const nodeTypes = { skill: SkillNode }

function describeMaster(
  master: NodeMaster,
  skillMasters: readonly SkillMasterSummary[],
  classMasters: readonly ClassMasterSummary[],
): NodePresentation {
  const effects = mapNodeEffects(master, skillMasters)
  const tags = master.tags ?? []
  return {
    label: stripMinecraftFormatting(master.name),
    icon: master.icon ?? '',
    effects,
    name: master.name,
    shortName: shortNodeName(master.name),
    master,
    pointType: pointType(master),
    className: nodeClassName(master, classMasters),
    level: nodeLevel(master),
    costTitle: mapNodeCost(master),
    title: [
      nodeTooltip(master, effects, classMasters),
      ...(tags.length > 0 ? ['', 'タグ:', ...tags.map(masterTagTooltip)] : []),
    ].join('\n'),
  }
}

const describeUnknown = (nodeId: string): NodePresentation => ({
  label: `Unknown ${nodeId}`,
  name: `Unknown ${nodeId}`,
  shortName: `Unknown ${nodeId}`,
  pointType: 'PP',
  className: '不明',
  icon: '',
  effects: [],
  costTitle: '—',
  title: `Unknown ${nodeId}`,
})

/**
 * 前回のノード配列を基に、入力が変わったノードだけを新しいオブジェクトへ置き換える。
 * 何も変わらなければ同じ配列を返す。
 */
function reconcileNodes(
  current: readonly SkillFlowNode[],
  descriptors: readonly NodeDescriptor[],
  iconRevision: number,
  selectedNodeId: string | null,
): SkillFlowNode[] {
  const previous = new Map(current.map((node) => [node.id, node]))
  let changed = current.length !== descriptors.length
  const next = descriptors.map((descriptor, index) => {
    const existing = previous.get(descriptor.id)
    const diameter = descriptor.diameter
    let node: SkillFlowNode
    if (!existing) {
      node = {
        id: descriptor.id,
        type: 'skill',
        selected: descriptor.id === selectedNodeId,
        position: { x: descriptor.x, y: descriptor.y },
        ariaLabel: descriptor.presentation.label,
        data: nodeData(descriptor, iconRevision),
        ...initialGeometry(diameter),
      }
    } else {
      const data = existing.data
      const sameData = data.presentation === descriptor.presentation && data.y === descriptor.level
        && data.root === descriptor.root && data.nodeSize === diameter && data.iconRevision === iconRevision
        && data.kind === descriptor.kind && data.labelAtZoom === descriptor.labelAtZoom && data.overlapOffset === descriptor.overlapOffset
        && (data.coincidentIds === descriptor.coincidentIds || data.coincidentIds.length === 1 && descriptor.coincidentIds.length === 1)
        && data.learned === descriptor.learned && data.selectionDisabled === descriptor.selectionDisabled && data.onToggleLearned === descriptor.onToggleLearned
      const samePosition = existing.position.x === descriptor.x && existing.position.y === descriptor.y
      node = sameData && samePosition ? existing : {
        ...existing,
        ariaLabel: descriptor.presentation.label,
        position: samePosition ? existing.position : { x: descriptor.x, y: descriptor.y },
        data: sameData ? data : nodeData(descriptor, iconRevision),
        ...(data.nodeSize === diameter ? null : initialGeometry(diameter)),
      }
    }
    if (current[index] !== node) changed = true
    return node
  })
  return changed ? next : current as SkillFlowNode[]
}

const nodeData = (descriptor: NodeDescriptor, iconRevision: number): SkillNodeData => ({
  presentation: descriptor.presentation,
  nodeId: descriptor.id,
  y: descriptor.level,
  root: descriptor.root,
  iconRevision,
  nodeSize: descriptor.diameter,
  labelAtZoom: descriptor.labelAtZoom,
  coincidentIds: descriptor.coincidentIds,
  overlapOffset: descriptor.overlapOffset,
  kind: descriptor.kind,
  learned: descriptor.learned,
  selectionDisabled: descriptor.selectionDisabled,
  onToggleLearned: descriptor.onToggleLearned,
})

/**
 * 要素の整数寸法を先に渡し、未描画のノードも接続・範囲計算の対象にする。
 * 表示する枠は実寸のまま、この要素の中心に置く。
 */
function initialGeometry(diameter: number): Pick<SkillFlowNode, 'width' | 'height' | 'measured' | 'handles'> {
  const size = Math.ceil(diameter)
  const handle = connectionHandleSize(diameter)
  const middle = (size - handle) / 2
  const near = -handle / 2
  const far = size - handle / 2
  const bounds = { width: handle, height: handle }
  const handles: NodeHandle[] = [
    { id: 'left', type: 'target', position: Position.Left, x: near, y: middle, ...bounds },
    { id: 'right', type: 'source', position: Position.Right, x: far, y: middle, ...bounds },
    { id: 'bottom', type: 'source', position: Position.Bottom, x: middle, y: far, ...bounds },
    { id: 'top', type: 'target', position: Position.Top, x: middle, y: near, ...bounds },
  ]
  return { width: size, height: size, measured: { width: size, height: size }, handles }
}

const connectionHandleSize = (diameter: number) => Math.min(HANDLE_SIZE, diameter * 0.2)

const selectNodes = (nodes: SkillFlowNode[], ids: ReadonlySet<string>) => {
  let changed = false
  const next = nodes.map((node) => {
    const selected = ids.has(node.id)
    if (Boolean(node.selected) === selected) return node
    changed = true
    return { ...node, selected }
  })
  return changed ? next : nodes
}

const isOverviewZoom = (state: ReactFlowState) => state.transform[2] < OVERVIEW_ZOOM
const zoomPercent = (state: ReactFlowState) => state.transform[2] >= 0.1
  ? Math.round(state.transform[2] * 100)
  : Number((state.transform[2] * 100).toPrecision(2))

function isNodeInView(
  node: { x: number; y: number; diameter: number },
  bounds: { width: number; height: number },
  viewport: { x: number; y: number; zoom: number },
): boolean {
  const x = viewport.x + node.x * viewport.zoom
  const y = viewport.y + node.y * viewport.zoom
  const radius = node.diameter * viewport.zoom / 2
  return x - radius < bounds.width && y - radius < bounds.height && x + radius > 0 && y + radius > 0
}

const edgeId = (source: string | null, target: string | null) => {
  const first = (source ?? '') < (target ?? '') ? source : target
  const second = (source ?? '') < (target ?? '') ? target : source
  return `edge:${first ?? ''}:${second ?? ''}`
}

type RoutedEdge = Pick<Edge, 'source' | 'target' | 'sourceHandle' | 'targetHandle'>

export const routeEdge = (
  firstNodeId: string,
  secondNodeId: string,
  placements: ReadonlyMap<string, { x: number; z: number }>,
): RoutedEdge => {
  const first = placements.get(firstNodeId)
  const second = placements.get(secondNodeId)
  return routeBetween(firstNodeId, secondNodeId, first && { x: first.x, y: first.z }, second && { x: second.x, y: second.z })
}

function routeBetween(
  firstNodeId: string,
  secondNodeId: string,
  first: { x: number; y: number } | undefined,
  second: { x: number; y: number } | undefined,
): RoutedEdge {
  if (!first || !second) {
    return { source: firstNodeId, target: secondNodeId, sourceHandle: 'right', targetHandle: 'left' }
  }

  const deltaX = second.x - first.x
  const deltaY = second.y - first.y
  if (Math.abs(deltaX) >= Math.abs(deltaY)) {
    return deltaX >= 0
      ? { source: firstNodeId, target: secondNodeId, sourceHandle: 'right', targetHandle: 'left' }
      : { source: secondNodeId, target: firstNodeId, sourceHandle: 'right', targetHandle: 'left' }
  }
  return deltaY >= 0
    ? { source: firstNodeId, target: secondNodeId, sourceHandle: 'bottom', targetHandle: 'top' }
    : { source: secondNodeId, target: firstNodeId, sourceHandle: 'bottom', targetHandle: 'top' }
}

const MINIMAP_SIZE = { width: 128, height: 100 }
const miniMapNodeClass = (node: Node) => String(node.data.kind)

const MapMiniDot = memo(function MapMiniDot({ x, y, width, height, className }: MiniMapNodeProps) {
  return <circle cx={x + width / 2} cy={y + height / 2} r={0.01} data-node-kind={className} className="tree-mini-dot" />
})

const edgeEnd = (node: SkillFlowNode) => ({
  ...node.position, diameter: node.data.nodeSize, square: node.data.presentation.pointType === 'CP',
})

const sameEdgeEnd = (a?: SkillFlowNode, b?: SkillFlowNode) => a === b
  || a?.position === b?.position && a?.data.nodeSize === b?.data.nodeSize && a?.data.presentation.pointType === b?.data.presentation.pointType

const edgeTypes = {
  tree: function TreeEdge({ id, data }: EdgeProps) {
    return typeof data?.path === 'string' && data.path
      ? <BaseEdge id={id} path={data.path} vectorEffect="non-scaling-stroke" /> : null
  },
}

function readGridPreference() {
  try { return globalThis.localStorage?.getItem(GRID_STORAGE_KEY) !== 'false' } catch { return true }
}

const mapTransform = (state: ReactFlowState) => state.transform

function MapGrid() {
  const [x, y, zoom] = useStore(mapTransform)
  const block = BLOCK_SCALE * zoom
  const gap = block < 8 ? block * 5 : block
  return <div className="tree-map-grid" aria-hidden="true" style={{
    backgroundSize: `${gap}px ${gap}px`,
    backgroundPosition: `${x % gap}px ${y % gap}px`,
    '--tree-axis-x': `${x}px`,
    '--tree-axis-z': `${y}px`,
  } as CSSProperties} />
}

function MapCoordinates({ canvasRef }: { canvasRef: { current: HTMLDivElement | null } }) {
  const { screenToFlowPosition } = useReactFlow()
  const store = useStoreApi()
  const [coordinates, setCoordinates] = useState('X — / Z —')
  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas) return
    let pointer: { x: number; y: number } | null = null
    const format = (value: number) => toTenthBlock(value / BLOCK_SCALE).toFixed(1)
    const updateCoordinates = () => {
      if (!pointer) return
      const { x, y } = screenToFlowPosition(pointer, { snapToGrid: false })
      setCoordinates(`X ${format(x)} / Z ${format(y)}`)
    }
    const track = (event: PointerEvent) => {
      pointer = { x: event.clientX, y: event.clientY }
      updateCoordinates()
    }
    const clear = () => { pointer = null; setCoordinates('X — / Z —') }
    canvas.addEventListener('pointermove', track)
    canvas.addEventListener('pointerleave', clear)
    const setLabelScale = (zoom: number) => canvas.style.setProperty('--tree-label-scale', String(1 / zoom))
    setLabelScale(store.getState().transform[2])
    const unsubscribe = store.subscribe((state, previous) => {
      if (state.transform[2] !== previous.transform[2]) setLabelScale(state.transform[2])
      if (state.transform !== previous.transform) updateCoordinates()
    })
    return () => {
      canvas.removeEventListener('pointermove', track)
      canvas.removeEventListener('pointerleave', clear)
      unsubscribe()
    }
  }, [canvasRef, screenToFlowPosition, store])
  return <div className="tree-map-coordinates" aria-label="ポインターのブロック座標">{coordinates}</div>
}

function NodeHoverSummary({ node, neighbors, masters }: {
  node: SkillFlowNode
  neighbors?: ReadonlySet<string>
  masters: ReadonlyMap<string, NodeMaster>
}) {
  const { presentation, kind, y, coincidentIds } = node.data
  return <div className="tree-map-hover" aria-label="ノードの要約">
    <div className="tree-map-hover-meta">
      <small>#{node.id} · {presentation.className} · {NODE_KIND_LABEL[kind]}</small>
      <strong>{presentation.costTitle}</strong>
    </div>
    <strong><MinecraftText value={presentation.name} /></strong>
    <MapEffectList effects={presentation.effects} />
    {coincidentIds.length > 1 && <p className="tree-map-muted">重なり {coincidentIds.length} · {coincidentIds
      .filter(id => id !== node.id)
      .map((id, index) => <span key={id}>{index > 0 && '、'}<MinecraftText value={masters.get(id)?.name ?? `#${id}`} /></span>)}</p>}
    {neighbors?.size ? <p className="tree-map-muted">接続 {neighbors.size}件 · {[...neighbors].slice(0, 3).map(id => {
      const name = masters.get(id)?.name
      return name ? stripMinecraftFormatting(name) : `#${id}`
    }).join('、')}{neighbors.size > 3 ? '…' : ''}</p> : null}
    <p className="tree-map-muted">Y {y}{presentation.level !== undefined && ` · プレイヤーLv. ${presentation.level} 以上`}</p>
  </div>
}

const toTenthBlock = (value: number) => Math.round(value * 10) / 10

const draggedPosition = (start: NodeDrag['delta'], delta: NodeDrag['delta']) => ({
  x: (start.x + delta.x) / 10,
  z: (start.z + delta.z) / 10,
})

const clampMenuPosition = (position: number, available: number, menuSize: number) => (
  Math.max(8, Math.min(position, Math.max(8, available - menuSize - 8)))
)
