import { useCallback, useEffect, useId, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import {
  Background, BackgroundVariant, ConnectionLineType, ConnectionMode, Controls, MarkerType, MiniMap, Panel, ReactFlow, ReactFlowProvider,
  applyEdgeChanges, applyNodeChanges, useReactFlow,
  type Connection, type Edge, type EdgeChange, type Node, type NodeChange,
} from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import { ApiError } from '../api/editorApi'
import { gameFlowApi } from '../api/gameFlowApi'
import { masterDataApi } from '../api/masterDataApi'
import {
  addNode, addShopPurchases, addableKinds, applySuggestions, canConnect, connect, freeSlot, planSuggestions, edgeKey, edgeLabelSuggestions, edgePorts, emptyGameFlow, flowIdPattern,
  gameFlowCategory, gameFlowOutline, gameFlowPath, gameFlowPort, kindLabels, nodeKind, parseGameFlow, refId, refKind, removeNodes, rowSpacing,
  serializeGameFlow, suggestFor, suggestedClasses, suggestionBatchSize, visibleFlow,
  type FlowSuggestion, type GameFlow, type GameFlowContext, type GameFlowEdge, type GameFlowNode, type GameFlowPort,
} from '../data/gameFlow'
import { usePanelDocument } from '../docking/dockPanel'
import type { ValidationIssue } from '../types/editor'
import type { MasterDocument, MasterFileSummary, MasterReference } from '../types/masterData'
import { stripMinecraftFormatting } from '../utils/minecraft'
import { useConfirmationDialog } from './ConfirmationDialog'
import { MasterEntityPicker, type MasterEntityPickerChoice } from './MasterEntityPicker'
import {
  FlowContextMenu, flowCopyCommands, flowNodeCopyTarget, flowReferenceCopyTarget, useGameFlowCopyMenu,
  type ContextCommand, type ResolvedRef,
} from './GameFlowContextMenu'
import { FlowNodeTooltip, gameFlowNodeTypes, shapeLegend, type FlowNodeData } from './GameFlowDiagramNode'
import { ClassToggles, EdgeProperties, FlowProperties, NodeProperties, ShopItemsDialog } from './GameFlowProperties'
import { referenceSymbol } from './MasterReferenceCollection'
import { MinecraftText } from './MinecraftText'
import { Modal } from './Modal'
import './gameFlow.css'

interface Props {
  active?: boolean
  onDirtyChange?: (dirty: boolean) => void
  onOpenMaster: (path: string) => void
}

type Selection = { type: 'node'; id: string } | { type: 'edge'; key: string } | null
/** position は右クリックした位置に置く場合の座標。 */
type PickerRequest = { kind: string; replace?: string; position?: { x: number; y: number } }

const sidebarStorageKey = 'astral.gameFlow.sidebarOpen'
const inspectorStorageKey = 'astral.gameFlow.inspectorOpen'
const readPanelOpen = (document: Document, key: string) => {
  try { return document.defaultView?.localStorage.getItem(key) !== '0' } catch { return true }
}
const savePanelOpen = (document: Document, key: string, open: boolean) => {
  try { document.defaultView?.localStorage.setItem(key, open ? '1' : '0') } catch { /* 保存できなくても表示は切り替える */ }
}

function AddFlowButton({ kind, disabled, onClick }: { kind: string; disabled: boolean; onClick: () => void }) {
  const label = kindLabels[kind]
  return <button type="button" className="game-flow-add" data-kind={kind} disabled={disabled}
    title={`${label}のノードを追加（右クリックでも図の上に置けます）`} aria-label={`${label}を追加`} onClick={onClick}>
    <span className="game-flow-add-swatch" data-shape={kind} aria-hidden="true" />{label}
  </button>
}

function FlowPanelToggle({ side, open, controls, onToggle }: { side: 'left' | 'right'; open: boolean; controls: string; onToggle: () => void }) {
  const left = side === 'left'
  const label = left ? '一覧' : 'プロパティ'
  const name = left ? 'フロー一覧' : label
  const shortcut = left ? '[' : ']'
  return <button type="button" className="button compact game-flow-panel-toggle" data-side={side} aria-pressed={open} aria-controls={controls}
    title={`${name}を${open ? '隠す' : '表示する'}（Ctrl+${shortcut}）`} onClick={onToggle}>
    <span className="game-flow-panel-icon" aria-hidden="true" />{label}
  </button>
}

const ghostPrefix = 'ghost:'

const errorMessage = (reason: unknown) => reason instanceof Error ? reason.message : String(reason)
const plain = (value: string) => stripMinecraftFormatting(value).trim()

export function GameFlowWorkspace(props: Props) {
  return <ReactFlowProvider><GameFlowEditor {...props} /></ReactFlowProvider>
}

function GameFlowEditor({ active = true, onDirtyChange, onOpenMaster }: Props) {
  const panelDocument = usePanelDocument()
  const flowApi = useReactFlow()
  const canvas = useRef<HTMLDivElement>(null)
  const toolbar = useRef<HTMLDivElement>(null)
  const classButton = useRef<HTMLButtonElement>(null)
  const classPopover = useRef<HTMLDivElement>(null)
  const panelId = useId()
  const sidebarId = `game-flow-sidebar-${panelId}`
  const inspectorId = `game-flow-inspector-${panelId}`
  const { confirm, dialog: confirmation } = useConfirmationDialog()

  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState('')
  const [prefixes, setPrefixes] = useState<Record<string, string>>({})
  const [files, setFiles] = useState<MasterFileSummary[]>([])
  const [candidates, setCandidates] = useState<MasterReference[]>([])
  const [context, setContext] = useState<GameFlowContext | null>(null)

  const [document, setDocument] = useState<MasterDocument | null>(null)
  const [flow, setFlow] = useState<GameFlow | null>(null)
  const [saved, setSaved] = useState('')
  const [past, setPast] = useState<GameFlow[]>([])
  const [future, setFuture] = useState<GameFlow[]>([])
  const [selection, setSelection] = useState<Selection>(null)
  const [classFilter, setClassFilter] = useState<string | null>(null)
  const [classPopoverOpen, setClassPopoverOpen] = useState(false)
  const [sidebarOpen, setSidebarOpen] = useState(() => readPanelOpen(panelDocument, sidebarStorageKey))
  const [inspectorOpen, setInspectorOpen] = useState(() => readPanelOpen(panelDocument, inspectorStorageKey))
  const toggleSidebar = () => {
    setSidebarOpen(!sidebarOpen)
    savePanelOpen(panelDocument, sidebarStorageKey, !sidebarOpen)
  }
  const toggleInspector = () => {
    setInspectorOpen(!inspectorOpen)
    savePanelOpen(panelDocument, inspectorStorageKey, !inspectorOpen)
  }
  const [view, setView] = useState<'graph' | 'text' | 'yaml'>('graph')
  const [issues, setIssues] = useState<ValidationIssue[]>([])
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [picker, setPicker] = useState<PickerRequest | null>(null)
  const [shopDialog, setShopDialog] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)
  /** 候補（ゲースト）を表示するか。Esc で、そのノードを選び直すまで隠す。 */
  const [suggestOn, setSuggestOn] = useState(true)
  const [suggestHiddenFor, setSuggestHiddenFor] = useState<string | null>(null)
  /** 右クリックメニュー。target は開いた対象（何もない所・ノード・矢印・候補）。 */
  const [menu, setMenu] = useState<{ x: number; y: number; target: { type: 'pane'; at: { x: number; y: number } }
    | { type: 'node'; id: string } | { type: 'edge'; key: string } | { type: 'ghost'; id: string } } | null>(null)
  const [yaml, setYaml] = useState('')
  const [detail, setDetail] = useState<{ id: string; anchor: HTMLElement; focus: boolean } | null>(null)
  const hoverTimer = useRef<number | undefined>(undefined)
  const clearHoverTimer = useCallback(() => {
    panelDocument.defaultView?.clearTimeout(hoverTimer.current)
  }, [panelDocument])
  useEffect(() => clearHoverTimer, [clearHoverTimer])

  const fileByPath = useMemo(() => new Map(files.map(file => [file.path, file])), [files])
  const flowFiles = useMemo(() => files.filter(file => file.category === gameFlowCategory && file.format === 'yaml')
    .sort((left, right) => left.path.localeCompare(right.path)), [files])

  /** `kind:id` → 名前と参照元ファイル。同じIDが複数ある場合は最初の1件。 */
  const references = useMemo(() => {
    const index = new Map<string, ResolvedRef>()
    for (const candidate of candidates) {
      if (!candidate.kind || !['/id', '/nodeId', '/structureId'].includes(candidate.pointer)) continue
      const key = `${candidate.kind}:${candidate.value}`
      if (index.has(key)) continue
      const file = fileByPath.get(candidate.path)
      index.set(key, { name: candidate.label || file?.name || candidate.value, file })
    }
    return index
  }, [candidates, fileByPath])
  const resolve = useCallback((ref: string) => references.get(`${refKind(ref, prefixes)}:${refId(ref)}`), [references, prefixes])
  const resolveName = useCallback((ref: string) => {
    const found = resolve(ref)
    return found ? plain(found.name) : undefined
  }, [resolve])
  const classes = useMemo(() => [...references.entries()]
    .filter(([key, entry]) => key.startsWith('class:') && key !== 'class:administrator' && entry.file)
    .sort(([, left], [, right]) => left.file!.path.localeCompare(right.file!.path, undefined, { numeric: true }))
    .map(([key, entry]) => ({ ref: key, name: plain(entry.name) || key })), [references])
  const className = useCallback((ref: string) => classes.find(entry => entry.ref === ref)?.name ?? resolveName(ref) ?? ref, [classes, resolveName])
  const flowClasses = useMemo(() => flow?.classes.length ? classes.filter(entry => flow.classes.includes(entry.ref)) : classes, [classes, flow])
  // 対象職業から外れた職業でフィルタしたまま、新しいノードへ割り当てない。
  useEffect(() => {
    if (classFilter && !flowClasses.some(entry => entry.ref === classFilter)) setClassFilter(null)
  }, [classFilter, flowClasses])

  const closeClassPopover = useCallback(() => {
    setClassPopoverOpen(false)
    classButton.current?.focus({ preventScroll: true })
  }, [])
  useLayoutEffect(() => {
    const bar = toolbar.current
    const button = classButton.current
    const popover = classPopover.current
    const panelWindow = panelDocument.defaultView
    if (!classPopoverOpen || !bar || !button || !popover || !panelWindow) return
    const position = () => {
      const anchor = button.getBoundingClientRect()
      const bounds = bar.getBoundingClientRect()
      popover.style.left = `${Math.max(8, Math.min(anchor.left - bounds.left, bar.clientWidth - popover.offsetWidth - 8))}px`
      popover.style.top = `${anchor.bottom - bounds.top + 4}px`
    }
    position()
    const focusTarget = popover.querySelector<HTMLInputElement>('input[type="checkbox"]') ?? popover
    focusTarget.focus()
    const observer = new panelWindow.ResizeObserver(position)
    observer.observe(bar)
    observer.observe(button)
    const onOutsidePointer = (event: PointerEvent) => {
      const target = event.target as globalThis.Node
      if (popover.contains(target) || button.contains(target)) return
      closeClassPopover()
      // 余白のクリックでドックのコンテナにフォーカスが移った場合も、ボタンへ戻す。
      panelWindow.requestAnimationFrame(() => {
        if (panelDocument.activeElement?.contains(button)) button.focus({ preventScroll: true })
      })
    }
    const onEscape = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return
      event.preventDefault()
      event.stopPropagation()
      closeClassPopover()
    }
    panelDocument.addEventListener('pointerdown', onOutsidePointer)
    panelDocument.addEventListener('keydown', onEscape, true)
    return () => {
      observer.disconnect()
      panelDocument.removeEventListener('pointerdown', onOutsidePointer)
      panelDocument.removeEventListener('keydown', onEscape, true)
    }
  }, [classPopoverOpen, closeClassPopover, panelDocument])

  const dirty = Boolean(flow && document && JSON.stringify(serializeGameFlow(flow)) !== saved)
  useEffect(() => { onDirtyChange?.(dirty) }, [dirty, onDirtyChange])

  const load = useCallback(async (reload = false) => {
    setLoading(true)
    setLoadError('')
    try {
      const [catalog, list, candidateList, flowContext] = await Promise.all([
        masterDataApi.catalog(reload), masterDataApi.files('', '', reload), masterDataApi.candidates(), gameFlowApi.context(reload),
      ])
      setPrefixes(catalog.referencePrefixes ?? {})
      setFiles(list)
      setCandidates(candidateList)
      setContext(flowContext)
      return list
    } catch (reason) {
      setLoadError(errorMessage(reason))
      return null
    } finally { setLoading(false) }
  }, [])

  const open = useCallback((next: MasterDocument) => {
    clearHoverTimer()
    setDetail(null)
    const parsed = parseGameFlow(next.content)
    setDocument(next)
    setFlow(parsed)
    setSaved(JSON.stringify(serializeGameFlow(parsed)))
    setPast([])
    setFuture([])
    setSelection(null)
    setClassFilter(null)
    setIssues(next.issues ?? [])
    setError('')
    requestAnimationFrame(() => void flowApi.fitView({ padding: .2, maxZoom: 1 }))
  }, [clearHoverTimer, flowApi])

  const openPath = useCallback(async (path: string) => {
    if (dirty && !await confirm('保存していないフローの変更を破棄して、別のフローを開きますか？')) return
    setBusy(true)
    try { open(await masterDataApi.file(path)) }
    catch (reason) { setError(errorMessage(reason)) }
    finally { setBusy(false) }
  }, [confirm, dirty, open])

  const started = useRef(false)
  useEffect(() => {
    if (!active || started.current) return
    started.current = true
    void load().then(list => {
      const first = list?.filter(file => file.category === gameFlowCategory && file.format === 'yaml').sort((a, b) => a.path.localeCompare(b.path))[0]
      if (first) void masterDataApi.file(first.path).then(open).catch(reason => setError(errorMessage(reason)))
    })
  }, [active, load, open])

  /** 編集を1手として記録する。 */
  const current = useRef<GameFlow | null>(null)
  current.current = flow
  const update = useCallback((change: (flow: GameFlow) => GameFlow) => {
    const before = current.current
    if (!before) return
    const next = change(before)
    if (next === before) return
    current.current = next
    setPast(history => [...history.slice(-99), before])
    setFuture([])
    setFlow(next)
  }, [])
  const undo = () => {
    if (!flow || !past.length) return
    setFuture(items => [flow, ...items])
    current.current = past.at(-1)!
    setFlow(past.at(-1)!)
    setPast(items => items.slice(0, -1))
  }
  const redo = () => {
    if (!flow || !future.length) return
    setPast(items => [...items, flow])
    current.current = future[0]
    setFlow(future[0])
    setFuture(items => items.slice(1))
  }

  // ---- キャンバス ----
  const visible = useMemo(() => flow ? visibleFlow(flow, classFilter) : null, [flow, classFilter])
  const nodeName = useCallback((node: GameFlowNode) => node.label?.trim() || (node.ref && resolve(node.ref)?.name) || node.ref || node.id, [resolve])
  const nodeById = useMemo(() => new Map(flow?.nodes.map(node => [node.id, node])), [flow])
  const connectedPorts = useMemo(() => {
    const ports = new Map<string, Set<GameFlowPort>>()
    const add = (id: string, port: GameFlowPort) => {
      const used = ports.get(id) ?? new Set<GameFlowPort>()
      used.add(port)
      ports.set(id, used)
    }
    for (const edge of flow?.edges ?? []) {
      const { fromPort, toPort } = edgePorts(edge, nodeById.get(edge.from), nodeById.get(edge.to))
      add(edge.from, fromPort)
      add(edge.to, toPort)
    }
    return ports
  }, [flow, nodeById])
  const derivedNodes = useMemo<Node<FlowNodeData>[]>(() => flow?.nodes.map(node => {
    const resolved = node.ref ? resolve(node.ref) : undefined
    const kind = nodeKind(node, prefixes, resolved?.file?.path)
    return {
      id: node.id, type: 'flow', ariaLabel: `${kindLabels[kind] ?? kind} ${plain(nodeName(node))}`, position: { x: node.x, y: node.y },
      selected: selection?.type === 'node' && selection.id === node.id,
      data: {
        name: nodeName(node), kind, file: resolved?.file, ref: node.ref, referenceName: resolved?.name, classNames: node.classes.map(className),
        note: node.note?.trim() || undefined, dimmed: !visible?.nodes.has(node.id), missing: Boolean(node.ref && !resolved),
        connectedPorts: connectedPorts.get(node.id),
      },
    }
  }) ?? [], [className, connectedPorts, flow, nodeName, prefixes, resolve, selection, visible])
  const derivedEdges = useMemo<Edge[]>(() => flow?.edges.map((edge, index) => {
    const key = edgeKey(edge)
    const chips = edge.classes.length ? ` [${edge.classes.map(className).join('・')}]` : ''
    const shown = visible?.edges.has(index) ?? true
    const ports = edgePorts(edge, nodeById.get(edge.from), nodeById.get(edge.to))
    return {
      id: key, source: edge.from, target: edge.to, type: 'smoothstep', sourceHandle: ports.fromPort, targetHandle: ports.toPort, label: (edge.label ?? '') + chips || undefined,
      selected: selection?.type === 'edge' && selection.key === key,
      markerEnd: { type: MarkerType.ArrowClosed, width: 18, height: 18 },
      className: shown ? undefined : 'dimmed', labelBgPadding: [6, 3] as [number, number], labelBgBorderRadius: 4,
    }
  }) ?? [], [className, flow, nodeById, selection, visible])
  const [nodes, setNodes] = useState<Node<FlowNodeData>[]>([])
  const [edges, setEdges] = useState<Edge[]>([])
  // 測定済みの寸法を引き継ぎ、編集のたびに全ノードを測り直さない。
  useEffect(() => setNodes(previous => {
    const measured = new Map(previous.map(node => [node.id, node.measured]))
    return derivedNodes.map(node => {
      const size = measured.get(node.id)
      return size ? { ...node, measured: size } : node
    })
  }), [derivedNodes])
  useEffect(() => setEdges(derivedEdges), [derivedEdges])

  // ---- 次に置くノードの候補（ゴースト表示・Tab で確定） ----
  // 候補の有無は Esc で隠していても求め、「候補を表示」で戻せるようにする。
  const suggestNodeId = selection?.type === 'node' && suggestOn ? selection.id : null
  const allSuggestions = useMemo(() => flow && suggestNodeId ? suggestFor(flow, suggestNodeId, context, prefixes) : null,
    [context, flow, prefixes, suggestNodeId])
  const suggestionNodeId = suggestNodeId && suggestHiddenFor !== suggestNodeId ? suggestNodeId : null
  const suggestions = suggestionNodeId ? allSuggestions : null
  const newNodeClasses = useMemo(() => classFilter ? [classFilter] : [], [classFilter])
  const ghosts = useMemo(() => {
    if (!flow || !suggestionNodeId || !suggestions) return null
    const batch = [...suggestions.next.slice(0, suggestionBatchSize), ...suggestions.previous.slice(0, suggestionBatchSize)]
    if (!batch.length) return null
    const preview = planSuggestions(flow, suggestionNodeId, batch, context, newNodeClasses)
    const added = new Map(preview.flow.nodes.slice(flow.nodes.length).map(node => [node.id, node]))
    const ghostId = (id: string) => added.has(id) ? ghostPrefix + id : id
    // 表示した位置を候補に持たせ、1件ずつ確定しても同じ位置に置く。
    const positioned = preview.placements.map(({ suggestion, nodeId }) => {
      const node = added.get(nodeId)
      return node ? { ...suggestion, position: { x: node.x, y: node.y } } : suggestion
    })
    const byGhostId = new Map<string, FlowSuggestion[]>()
    preview.placements.forEach((placement, index) => {
      const nodeKey = ghostId(placement.nodeId)
      const edge = placement.suggestion.direction === 'next'
        ? { from: suggestionNodeId, to: placement.nodeId } : { from: placement.nodeId, to: suggestionNodeId }
      for (const key of [nodeKey, ghostPrefix + edgeKey(edge)])
        byGhostId.set(key, [...byGhostId.get(key) ?? [], positioned[index]])
    })
    const ghostNodes: Node<FlowNodeData>[] = [...added.values()].map(node => {
      const resolved = node.ref ? resolve(node.ref) : undefined
      const labels = [...new Set((byGhostId.get(ghostId(node.id)) ?? []).map(entry => entry.label))].join('・')
      return {
        id: ghostId(node.id), type: 'flow', position: { x: node.x, y: node.y },
        draggable: false, selectable: false, connectable: false, focusable: false,
        data: {
          name: resolved?.name ?? node.ref ?? node.id, kind: nodeKind(node, prefixes, resolved?.file?.path), file: resolved?.file,
          ref: node.ref, referenceName: resolved?.name, classNames: node.classes.map(className),
          dimmed: false, missing: Boolean(node.ref && !resolved), suggestion: labels,
        },
      }
    })
    const positionOf = (id: string) => added.get(id) ?? nodeById.get(id)
    const ghostEdges: Edge[] = preview.flow.edges.slice(flow.edges.length).map(edge => {
      const ports = edgePorts(edge, positionOf(edge.from), positionOf(edge.to))
      return {
        id: ghostPrefix + edgeKey(edge), source: ghostId(edge.from), target: ghostId(edge.to), label: edge.label,
        type: 'smoothstep', sourceHandle: ports.fromPort, targetHandle: ports.toPort,
        className: 'ghost', animated: true, selectable: false, focusable: false,
        markerEnd: { type: MarkerType.ArrowClosed, width: 18, height: 18 }, labelBgPadding: [6, 3], labelBgBorderRadius: 4,
      }
    })
    return {
      nodes: ghostNodes, edges: ghostEdges, byGhostId,
      next: positioned.filter(entry => entry.direction === 'next'), previous: positioned.filter(entry => entry.direction === 'previous'),
    }
  }, [className, context, flow, newNodeClasses, nodeById, prefixes, resolve, suggestionNodeId, suggestions])
  /** 候補を配置し、ズームはそのままで配置したノードが見える位置へ寄せる。 */
  const placeSuggestions = (nodeId: string, list: readonly FlowSuggestion[]) => {
    const before = current.current
    if (!before || !list.length) return
    const after = applySuggestions(before, nodeId, list, context, newNodeClasses)
    update(() => after)
    const targets = new Set([nodeId, ...after.nodes.slice(before.nodes.length).map(node => node.id),
      ...list.map(entry => entry.existingNodeId).filter((id): id is string => Boolean(id))])
    requestAnimationFrame(() => void flowApi.fitView({
      nodes: [...targets].map(id => ({ id })), padding: .3, maxZoom: flowApi.getZoom(), duration: 250,
    }))
  }
  const acceptSuggestions = (list: readonly FlowSuggestion[]) => { if (suggestionNodeId) placeSuggestions(suggestionNodeId, list) }
  const displayNodes = useMemo(() => ghosts ? [...nodes, ...ghosts.nodes] : nodes, [ghosts, nodes])
  const detailNode = detail ? displayNodes.find(node => node.id === detail.id) : undefined
  const displayEdges = useMemo(() => ghosts ? [...edges, ...ghosts.edges] : edges, [ghosts, edges])

  const onNodesChange = useCallback((changes: NodeChange<Node<FlowNodeData>>[]) =>
    setNodes(current => applyNodeChanges(changes.filter(change => change.type !== 'remove'), current)), [])
  const onEdgesChange = useCallback((changes: EdgeChange[]) =>
    setEdges(current => applyEdgeChanges(changes.filter(change => change.type !== 'remove'), current)), [])
  const onNodeDragStop = useCallback((_: unknown, __: Node, moved: Node[]) => {
    const positions = new Map(moved.map(node => [node.id, node.position]))
    update(current => ({
      ...current,
      nodes: current.nodes.map(node => {
        const position = positions.get(node.id)
        return position && (Math.round(position.x) !== node.x || Math.round(position.y) !== node.y)
          ? { ...node, x: Math.round(position.x), y: Math.round(position.y) } : node
      }),
    }))
  }, [update])
  const isValidConnection = useCallback((connection: Connection | Edge) => {
    const currentFlow = current.current
    const from = nodeById.get(connection.source)
    const to = nodeById.get(connection.target)
    return Boolean(currentFlow && from && to && nodeKind(from) !== 'note' && nodeKind(to) !== 'note'
      && canConnect(currentFlow, connection.source, connection.target))
  }, [nodeById])
  const onConnect = useCallback((connection: Connection) => {
    if (!isValidConnection(connection)) return
    update(current => {
      const from = current.nodes.find(node => node.id === connection.source)
      const label = from && refKind(from.ref, prefixes) === 'shop' ? '購入' : undefined
      const ports = { fromPort: gameFlowPort(connection.sourceHandle), toPort: gameFlowPort(connection.targetHandle) }
      return connect(current, connection.source, connection.target, label, classFilter ? [classFilter] : [], ports)
    })
    setSelection({ type: 'edge', key: edgeKey({ from: connection.source, to: connection.target }) })
  }, [classFilter, isValidConnection, prefixes, update])
  const deleteSelection = () => {
    if (!selection) return
    if (selection.type === 'node') update(current => removeNodes(current, new Set([selection.id])))
    else update(current => ({ ...current, edges: current.edges.filter(edge => edgeKey(edge) !== selection.key) }))
    setSelection(null)
  }

  const centerPosition = () => {
    const rect = canvas.current?.getBoundingClientRect()
    const point = rect ? flowApi.screenToFlowPosition({ x: rect.left + rect.width / 2, y: rect.top + rect.height / 2 }) : { x: 0, y: 0 }
    const offset = (flow?.nodes.length ?? 0) % 5 * 24
    return { x: Math.round(point.x - 75 + offset), y: Math.round(point.y - 40 + offset) }
  }
  const addReference = (kind: string, id: string, position?: { x: number; y: number }) => {
    if (!current.current) return
    const result = addNode(current.current, {
      ref: `${kind}:${id}`, ...(position ?? centerPosition()),
      classes: kind === 'item' ? suggestedClasses(context, id) : classFilter ? [classFilter] : [],
    })
    update(() => result.flow)
    setSelection({ type: 'node', id: result.id })
  }
  const addLocalNode = (kind: 'action' | 'note', position?: { x: number; y: number }) => {
    if (!current.current) return
    const result = addNode(current.current, {
      label: kindLabels[kind], role: kind === 'action' ? kind : undefined,
      ...(position ?? centerPosition()), classes: newNodeClasses,
    })
    update(() => result.flow)
    setSelection({ type: 'node', id: result.id })
  }
  const changeNode = (id: string, change: Partial<GameFlowNode>) =>
    update(current => ({ ...current, nodes: current.nodes.map(node => node.id === id ? { ...node, ...change } : node) }))
  const changeEdge = (key: string, change: Partial<GameFlowEdge>) =>
    update(current => ({ ...current, edges: current.edges.map(edge => edgeKey(edge) === key ? { ...edge, ...change } : edge) }))

  const pickerChoices = useMemo<MasterEntityPickerChoice[]>(() => {
    if (!picker) return []
    return [...references.entries()].flatMap(([key, entry]) => {
      if (!key.startsWith(picker.kind + ':') || key === 'class:administrator') return []
      const id = key.slice(picker.kind.length + 1)
      return [{
        id, name: entry.name, category: entry.file?.category, icon: entry.file?.icon, customModelData: entry.file?.customModelData,
        iconTexture: entry.file?.iconTexture, iconModel: entry.file?.iconModel, symbol: referenceSymbol(picker.kind),
      }]
    })
  }, [picker, references])
  const selectPicked = (id: string) => {
    if (picker?.replace) {
      // 別名（sh: など）で書かれた参照は、その接頭辞のまま差し替える。
      const original = current.current?.nodes.find(node => node.id === picker.replace)?.ref
      const prefix = original?.includes(':') ? original.slice(0, original.indexOf(':')) : picker.kind
      changeNode(picker.replace, { ref: `${prefix}:${id}` })
    }
    else if (picker) addReference(picker.kind, id, picker.position)
    setPicker(null)
  }

  // ---- 保存 ----
  const save = async () => {
    if (!flow || !document) return
    setBusy(true)
    setError('')
    setMessage('')
    try {
      const rendered = await masterDataApi.render(document.path, serializeGameFlow(flow), document.raw)
      const report = await masterDataApi.validate(document.path, rendered.raw)
      setIssues(report.issues)
      if (!report.isValid) { setError('検証エラーがあるため保存できませんでした。'); return }
      const next = await masterDataApi.save(document.path, rendered.raw, document.revision)
      if (current.current === flow) open(next)
      else {
        // 保存中に続けた編集は残し、保存済みの版と比較用の内容だけを更新する。
        setDocument(next)
        setSaved(JSON.stringify(serializeGameFlow(parseGameFlow(next.content))))
      }
      setIssues(report.issues)
      setMessage(`${document.path} を保存しました。`)
    } catch (reason) {
      setError(reason instanceof ApiError && reason.status === 409
        ? '同じフローが別の操作で変更されました。再読込してから編集し直してください。'
        : errorMessage(reason))
    } finally { setBusy(false) }
  }
  const create = async (id: string, name: string) => {
    const path = gameFlowPath(id)
    setBusy(true)
    setError('')
    try {
      const rendered = await masterDataApi.render(path, serializeGameFlow(emptyGameFlow(id, name)))
      const header = `# design:\n#   motif: ${JSON.stringify('進行設計 / ' + name)}\n#   progression: 0\n`
      const created = await masterDataApi.create({ path, raw: header + rendered.raw })
      setFiles(await masterDataApi.files('', '', true))
      open(created)
      setCreating(false)
      setMessage(`${path} を作成しました。`)
    } catch (reason) { setError(errorMessage(reason)) }
    finally { setBusy(false) }
  }
  const revert = async () => {
    if (!document || !await confirm('保存していない変更を破棄して、保存済みの内容に戻しますか？')) return
    try { open(await masterDataApi.file(document.path)) } catch (reason) { setError(errorMessage(reason)) }
  }
  const reloadAll = async () => {
    if (dirty && !await confirm('保存していない変更を破棄して、マスターを再読込しますか？')) return
    const list = await load(true)
    if (list && document) await masterDataApi.file(document.path).then(open).catch(reason => setError(errorMessage(reason)))
  }

  // ---- テキスト / 原稿 ----
  const outline = useMemo(() => flow ? gameFlowOutline(flow, resolveName, {
    prefixes, classRef: classFilter, className, masterPath: ref => resolve(ref)?.file?.path,
  }) : '', [className, classFilter, flow, prefixes, resolve, resolveName])
  useEffect(() => {
    if (view !== 'yaml' || !flow || !document) return
    let live = true
    const timer = setTimeout(() => {
      void masterDataApi.render(document.path, serializeGameFlow(flow), document.raw)
        .then(result => { if (live) setYaml(result.raw) })
        .catch(reason => { if (live) setYaml(`# 原稿を作成できませんでした: ${errorMessage(reason)}`) })
    }, 250)
    return () => { live = false; clearTimeout(timer) }
  }, [document, flow, view])
  const [manualCopy, setManualCopy] = useState<{ value: string; dialog: Element | null } | null>(null)
  const copy = async (value: string) => {
    const dialog = panelDocument.activeElement?.closest('[role="dialog"]') ?? null
    setManualCopy(null)
    try {
      await panelDocument.defaultView!.navigator.clipboard.writeText(value)
      setMessage('クリップボードにコピーしました。')
    } catch {
      setManualCopy({ value, dialog })
    }
  }
  const { onCopyContextMenu, copyMenu } = useGameFlowCopyMenu(copy)
  const copyFallback = manualCopy && <div className="game-flow-error" role="alert">
    <label>下の欄を選択してコピーしてください。<textarea aria-label="手動コピー用の値" readOnly value={manualCopy.value}
      onFocus={event => event.currentTarget.select()} /></label>
    <button type="button" className="button compact subtle" onClick={() => setManualCopy(null)}>閉じる</button>
  </div>
  const referenceTarget = (ref: string) => flowReferenceCopyTarget(ref, resolve(ref))
  const currentFileTarget = { name: flow?.name, path: document?.path, values: [{ label: 'フローIDをコピー', value: flow?.id }] }

  const selectedNode = selection?.type === 'node' ? flow?.nodes.find(node => node.id === selection.id) : undefined
  const selectedEdge = selection?.type === 'edge' ? flow?.edges.find(edge => edgeKey(edge) === selection.key) : undefined

  // ---- 右クリックメニュー ----
  const closeMenu = useCallback(() => setMenu(null), [])
  /** 表示中のゴーストと同じ位置に置けるよう、候補に表示位置を付ける。 */
  const withShownPosition = (list: readonly FlowSuggestion[]) =>
    list.map(entry => ghosts?.[entry.direction].find(shown => shown.ref === entry.ref) ?? entry)
  const contextCommands = (target: NonNullable<typeof menu>['target']): { title: string; commands: ContextCommand[] } => {
    if (!flow) return { title: '', commands: [] }
    if (target.type === 'pane') return {
      title: 'ここに追加',
      commands: [
        ...addableKinds.map(kind => ({ label: `${kindLabels[kind]}を置く…`, run: () => setPicker({ kind, position: target.at }) })),
        { label: '操作を置く', run: () => addLocalNode('action', target.at) },
        { label: 'メモを置く', run: () => addLocalNode('note', target.at) },
        null,
        { label: '全体を表示', run: () => void flowApi.fitView({ padding: .2, maxZoom: 1, duration: 250 }) },
        { label: suggestOn ? '候補の表示をオフにする' : '候補の表示をオンにする', run: () => { setSuggestOn(value => !value); setSuggestHiddenFor(null) } },
      ],
    }
    if (target.type === 'ghost') {
      const list = ghosts?.byGhostId.get(target.id) ?? []
      const suggestedRef = list[0]?.ref
      return {
        title: '候補',
        commands: [
          ...(suggestedRef ? flowCopyCommands(referenceTarget(suggestedRef), copy) : []),
          { label: 'この候補を配置', disabled: !list.length, run: () => acceptSuggestions(list) },
          { label: `次の候補をすべて配置（${ghosts?.next.length ?? 0}件）`, disabled: !ghosts?.next.length, run: () => acceptSuggestions(ghosts?.next ?? []) },
          { label: `入手元・前の候補をすべて配置（${ghosts?.previous.length ?? 0}件）`, disabled: !ghosts?.previous.length, run: () => acceptSuggestions(ghosts?.previous ?? []) },
          null,
          { label: '候補を隠す', disabled: !suggestionNodeId, run: () => suggestionNodeId && setSuggestHiddenFor(suggestionNodeId) },
        ],
      }
    }
    if (target.type === 'edge') {
      const edge = flow.edges.find(entry => edgeKey(entry) === target.key)
      if (!edge) return { title: '矢印', commands: [] }
      const from = nodeById.get(edge.from)
      const to = nodeById.get(edge.to)
      const route = `${from ? plain(nodeName(from)) : edge.from} → ${to ? plain(nodeName(to)) : edge.to}`
      const reversed = flow.edges.some(entry => entry.from === edge.to && entry.to === edge.from)
      return {
        title: route,
        commands: [
          ...flowCopyCommands({ values: [
            { label: '矢印の向きをコピー', value: route }, { label: 'ラベルをコピー', value: edge.label },
            { label: '始点の参照をコピー', value: from?.ref }, { label: '終点の参照をコピー', value: to?.ref },
          ] }, copy),
          null,
          ...edgeLabelSuggestions.map(label => ({ label: `${edge.label === label ? '✓ ' : ''}ラベル: ${label}`, run: () => changeEdge(target.key, { label }) })),
          { label: 'ラベルを外す', disabled: !edge.label, run: () => changeEdge(target.key, { label: undefined }) },
          null,
          { label: '向きを反転', disabled: reversed, run: () => {
            const opposite = { from: edge.to, to: edge.from, fromPort: edge.toPort, toPort: edge.fromPort }
            changeEdge(target.key, opposite)
            setSelection({ type: 'edge', key: edgeKey(opposite) })
          } },
          { label: '矢印を削除', run: () => { update(current => ({ ...current, edges: current.edges.filter(entry => edgeKey(entry) !== target.key) })); setSelection(null) } },
        ],
      }
    }
    const node = flow.nodes.find(entry => entry.id === target.id)
    if (!node) return { title: 'ノード', commands: [] }
    const resolved = node.ref ? resolve(node.ref) : undefined
    const kind = nodeKind(node, prefixes, resolved?.file?.path)
    const found = node.ref ? suggestFor(flow, node.id, context, prefixes) : { next: [], previous: [] }
    const nextBatch = withShownPosition(found.next.slice(0, suggestionBatchSize))
    const previousBatch = withShownPosition(found.previous.slice(0, suggestionBatchSize))
    const shop = kind === 'shop' ? context?.shops.find(entry => entry.id === refId(node.ref)) : undefined
    return {
      title: `${kindLabels[kind] ?? kind}: ${plain(nodeName(node))}`,
      commands: [
        ...flowCopyCommands(flowNodeCopyTarget(node, resolved), copy),
        null,
        { label: `次の候補を配置（${nextBatch.length}件）`, disabled: !nextBatch.length, run: () => placeSuggestions(node.id, nextBatch) },
        { label: `入手元・前の候補を配置（${previousBatch.length}件）`, disabled: !previousBatch.length, run: () => placeSuggestions(node.id, previousBatch) },
        ...(kind === 'shop' ? [{ label: '商品を購入アイテムとして追加…', disabled: !shop?.items.length, run: () => setShopDialog(node.id) }] : []),
        {
          label: '近くにメモを置く', run: () => {
            if (current.current) addLocalNode('note', freeSlot(current.current, node.x + 80, node.y + rowSpacing))
          },
        },
        null,
        ...(node.ref ? [{ label: '参照を変更…', run: () => setPicker({ kind: refKind(node.ref, prefixes), replace: node.id }) }] : []),
        ...(resolved?.file ? [{ label: 'マスターを開く', run: () => onOpenMaster(resolved.file!.path) }] : []),
        null,
        { label: kind === 'note' ? 'メモを削除' : 'ノードを削除', run: () => { update(current => removeNodes(current, new Set([node.id]))); setSelection(null) } },
      ],
    }
  }

  return <div className="game-flow-host"><div className="game-flow" data-sidebar={sidebarOpen ? 'open' : 'closed'} data-inspector={inspectorOpen ? 'open' : 'closed'}>
    <aside id={sidebarId} className="game-flow-sidebar" aria-label="フロー一覧" hidden={!sidebarOpen}>
      <div className="game-flow-sidebar-head">
        <h2>ゲーム進行フロー</h2>
        <button type="button" className="button compact" disabled={busy || loading} onClick={async () => {
          if (dirty && !await confirm('保存していないフローの変更を破棄して、新しいフローを作りますか？')) return
          setCreating(true)
        }}>＋ 新規</button>
      </div>
      {loading && <p className="game-flow-muted">読み込んでいます…</p>}
      {loadError && <div className="game-flow-error" role="alert">{loadError}<button type="button" className="button compact" onClick={() => void load(true)}>再試行</button></div>}
      <nav className="game-flow-list">
        {flowFiles.map(file => <button key={file.path} type="button" className="game-flow-list-item" aria-current={file.path === document?.path ? 'true' : undefined}
          onContextMenu={onCopyContextMenu({ name: file.name, path: file.path, values: [{ label: 'フローIDをコピー', value: file.id }] }, 'フロー')}
          disabled={busy} onClick={() => file.path !== document?.path && void openPath(file.path)}>
          <MinecraftText value={file.name || file.id || file.path} />
          <small>{file.path.slice(gameFlowCategory.length + 1)}</small>
        </button>)}
        {!loading && !flowFiles.length && <p className="game-flow-muted">フローはまだありません。「＋ 新規」から作成します。</p>}
      </nav>
      <p className="game-flow-muted game-flow-location" onContextMenu={onCopyContextMenu({ path: gameFlowCategory }, '保存先')}>保存先: <code>40_filebase/{gameFlowCategory}/</code></p>
    </aside>

    <section className="game-flow-main">
      <div className="game-flow-toolbar" ref={toolbar}>
        <div className="game-flow-toolbar-row add">
          <div className="game-flow-toolbar-group game-flow-list-toggle">
            <FlowPanelToggle side="left" open={sidebarOpen} controls={sidebarId} onToggle={toggleSidebar} />
            {!sidebarOpen && flow && <span className="game-flow-current-name" title={plain(flow.name)}
              onContextMenu={onCopyContextMenu(currentFileTarget, 'フロー')}>{plain(flow.name)}</span>}
          </div>
          <div className="game-flow-toolbar-group game-flow-add-group" role="group" aria-label="追加">
            <span className="game-flow-toolbar-heading">追加</span>
            {addableKinds.map(kind => <AddFlowButton key={kind} kind={kind} disabled={!flow || busy} onClick={() => setPicker({ kind })} />)}
            <span className="game-flow-toolbar-divided">
              <AddFlowButton kind="action" disabled={!flow || busy} onClick={() => addLocalNode('action')} />
              <AddFlowButton kind="note" disabled={!flow || busy} onClick={() => addLocalNode('note')} />
            </span>
          </div>
          <FlowPanelToggle side="right" open={inspectorOpen} controls={inspectorId} onToggle={toggleInspector} />
        </div>
        <div className="game-flow-toolbar-row controls">
          <div className="game-flow-toolbar-group game-flow-class-group" data-filtered={Boolean(classFilter)} role="group" aria-label="職業">
            <span className="game-flow-toolbar-heading">職業</span>
            <button ref={classButton} type="button" className="button compact game-flow-class-button" data-kind="class" disabled={!flow}
              onContextMenu={onCopyContextMenu({ values: [{ label: '対象職業をコピー', value: flow?.classes.length ? flow.classes.join('\n') : '全職業' }] }, '対象職業')}
              aria-haspopup="dialog" aria-expanded={classPopoverOpen} onClick={() => classPopoverOpen ? closeClassPopover() : setClassPopoverOpen(true)}>
              <span className="game-flow-add-swatch" data-shape="class" aria-hidden="true" />{flow?.classes.length ? `対象職業 ${flow.classes.length}` : '対象職業: 全職業'}
            </button>
            {flow?.classes.length ? <div className="game-flow-class-chips" role="group" aria-label="職業で絞り込み">
              <button type="button" className="button compact game-flow-class-chip" aria-pressed={!classFilter} onClick={() => setClassFilter(null)}>すべて</button>
              {flowClasses.map(entry => <button key={entry.ref} type="button" className="button compact game-flow-class-chip" aria-pressed={classFilter === entry.ref}
                onContextMenu={onCopyContextMenu(referenceTarget(entry.ref), '職業')}
                onClick={() => setClassFilter(classFilter === entry.ref ? null : entry.ref)}>{entry.name}</button>)}
            </div> : <select aria-label="職業で絞り込み" value={classFilter ?? ''} disabled={!flow}
              onContextMenu={onCopyContextMenu(classFilter ? referenceTarget(classFilter) : { name: 'すべて' }, '職業')}
              onChange={event => setClassFilter(event.target.value || null)}>
              <option value="">すべて</option>
              {flowClasses.map(entry => <option key={entry.ref} value={entry.ref}>{entry.name}</option>)}
            </select>}
          </div>
          <div className="game-flow-toolbar-group game-flow-toolbar-divided" role="group" aria-label="表示設定">
            <div className="game-flow-tabs" role="tablist" aria-label="表示">
              {([['graph', '図'], ['text', 'テキスト'], ['yaml', 'YAML']] as const).map(([id, label]) =>
                <button key={id} type="button" role="tab" aria-selected={view === id} className="button compact" onClick={() => setView(id)}>{label}</button>)}
            </div>
            <button type="button" className="button compact" aria-pressed={suggestOn} disabled={!flow}
              title="選んだノードから、次に置くマスター（ドロップ・出現モブなど）を候補として表示します"
              onClick={() => { setSuggestOn(value => !value); setSuggestHiddenFor(null) }}>候補 {suggestOn ? 'ON' : 'OFF'}</button>
          </div>
          <div className="game-flow-toolbar-group game-flow-toolbar-divided" role="group" aria-label="履歴">
            <button type="button" className="button compact" disabled={!past.length} title="Ctrl+Z" onClick={undo}>元に戻す</button>
            <button type="button" className="button compact" disabled={!future.length} title="Ctrl+Y" onClick={redo}>やり直す</button>
          </div>
          <div className="game-flow-toolbar-group game-flow-toolbar-divided game-flow-file-group" role="group" aria-label="ファイル操作">
            <button type="button" className="button compact" disabled={busy || loading} onClick={() => void reloadAll()}>再読込</button>
            <button type="button" className="button compact" disabled={!dirty || busy} onClick={() => void revert()}>破棄</button>
            <span className="game-flow-save-group">
              {dirty && <span className="game-flow-unsaved">未保存</span>}
              <button type="button" className="button compact primary" disabled={!dirty || busy} onClick={() => void save()}>{busy ? '処理中…' : '保存'}</button>
            </span>
          </div>
        </div>
        {classPopoverOpen && flow && <div ref={classPopover} className="game-flow-class-popover" role="dialog" aria-label="対象職業" tabIndex={-1}>
          <p className="game-flow-muted">このフローで扱う職業。職業フィルタの候補になります。</p>
          <ClassToggles value={flow.classes} classes={classes} resolve={resolve} copyContextMenu={onCopyContextMenu}
            emptyLabel="未指定: 全職業" onChange={classes => update(current => ({ ...current, classes }))} />
          <div className="game-flow-actions">
            <button type="button" className="button compact" disabled={!flow.classes.length} onClick={() => update(current => ({ ...current, classes: [] }))}>すべて外す（全職業）</button>
            <button type="button" className="button compact" onClick={closeClassPopover}>閉じる</button>
          </div>
        </div>}
      </div>
      {(error || message) && <div className={error ? 'game-flow-error' : 'game-flow-message'} role={error ? 'alert' : 'status'}>
        {error || message}<button type="button" className="button compact subtle" onClick={() => { setError(''); setMessage('') }}>閉じる</button>
      </div>}
      {manualCopy?.dialog?.isConnected ? createPortal(copyFallback, manualCopy.dialog) : copyFallback}
      {issues.length > 0 && <ul className="game-flow-issues" aria-label="検証結果">
        {issues.map((issue, index) => <li key={index} data-severity={issue.severity} onContextMenu={onCopyContextMenu({
          path: document?.path, values: [{ label: '項目パスをコピー', value: issue.path || '/' }, { label: 'メッセージをコピー', value: issue.message }],
        }, '検証結果')}><code>{issue.path || '/'}</code> {issue.message}</li>)}
      </ul>}
      {/* ショートカットはキャンバス内のキー操作だけで受け付ける（xyflow の全体キー監視は使わない）。 */}
      <div className="game-flow-body" ref={canvas} tabIndex={0} hidden={view !== 'graph'}
        onPointerDownCapture={() => { clearHoverTimer(); setDetail(null) }}
        onFocusCapture={event => {
          const target = event.target
          if (target.dataset.id && target.matches('.react-flow__node:focus-visible')) {
            clearHoverTimer()
            setDetail({ id: target.dataset.id, anchor: target, focus: true })
          }
        }}
        onBlurCapture={event => {
          setDetail(current => current?.focus && current.anchor === event.target ? null : current)
        }}
        onKeyDown={event => {
          if (event.key === 'Escape') { clearHoverTimer(); setDetail(null) }
          if ((event.target as HTMLElement).closest('input, textarea, select, [contenteditable="true"]')) return
          if (event.ctrlKey && !event.altKey && !event.metaKey && (event.key === '[' || event.key === ']')) {
            event.preventDefault()
            if (!event.repeat) {
              if (event.key === '[') toggleSidebar()
              else toggleInspector()
            }
            return
          }
          if ((event.key === 'Delete' || event.key === 'Backspace') && !event.ctrlKey && !event.metaKey && !event.altKey) {
            event.preventDefault()
            deleteSelection()
            return
          }
          // Tab: 次の候補を配置 / Shift+Tab: 前（入手元）の候補を配置 / Esc: 候補を隠す
          // 図そのもの・ノードにフォーカスがあるときだけ。ズームボタンなどからは通常の Tab 移動のまま。
          const target = event.target as HTMLElement
          const onCanvas = target === canvas.current || target.classList.contains('react-flow__node') || target.classList.contains('react-flow__pane')
          if (event.key === 'Tab' && onCanvas && !event.ctrlKey && !event.metaKey && !event.altKey && ghosts) {
            const list = event.shiftKey ? ghosts.previous : ghosts.next
            if (list.length) { event.preventDefault(); acceptSuggestions(list) }
            return
          }
          if (event.key === 'Escape' && ghosts && suggestionNodeId) { event.preventDefault(); setSuggestHiddenFor(suggestionNodeId); return }
          if (!(event.ctrlKey || event.metaKey)) return
          if (event.key === 'z') { event.preventDefault(); undo() }
          if (event.key === 'y') { event.preventDefault(); redo() }
      }}>
        {flow ? <ReactFlow nodes={displayNodes} edges={displayEdges} nodeTypes={gameFlowNodeTypes} onNodesChange={onNodesChange} onEdgesChange={onEdgesChange}
          onNodeDragStop={onNodeDragStop} onConnect={onConnect} isValidConnection={isValidConnection} deleteKeyCode={null}
          connectionMode={ConnectionMode.Loose} connectionLineType={ConnectionLineType.SmoothStep}
          onNodeMouseEnter={(event, node) => {
            clearHoverTimer()
            if (event.buttons !== 0) return
            const anchor = event.currentTarget as HTMLElement
            hoverTimer.current = panelDocument.defaultView?.setTimeout(() => setDetail({ id: node.id, anchor, focus: false }), 250)
          }}
          onNodeMouseLeave={(_, node) => {
            clearHoverTimer()
            setDetail(current => current?.id === node.id && !current.focus ? null : current)
          }}
          onMoveStart={() => { clearHoverTimer(); setDetail(current => current?.focus ? current : null) }}
          onNodeClick={(_, node) => {
            const list = ghosts?.byGhostId.get(node.id)
            if (list) acceptSuggestions(list)
            else if (!node.id.startsWith(ghostPrefix)) { setSelection({ type: 'node', id: node.id }); setSuggestHiddenFor(null) }
          }}
          onEdgeClick={(_, edge) => {
            const list = ghosts?.byGhostId.get(edge.id)
            if (list) acceptSuggestions(list)
            else if (!edge.id.startsWith(ghostPrefix)) setSelection({ type: 'edge', key: edge.id })
          }}
          onPaneClick={() => { setSelection(null); canvas.current?.focus({ preventScroll: true }) }}
          onPaneContextMenu={event => {
            event.preventDefault()
            const at = flowApi.screenToFlowPosition({ x: event.clientX, y: event.clientY })
            setMenu({ x: event.clientX, y: event.clientY, target: { type: 'pane', at: { x: Math.round(at.x - 75), y: Math.round(at.y - 54) } } })
          }}
          onNodeContextMenu={(event, node) => {
            event.preventDefault()
            clearHoverTimer()
            setDetail(null)
            if (node.id.startsWith(ghostPrefix)) { setMenu({ x: event.clientX, y: event.clientY, target: { type: 'ghost', id: node.id } }); return }
            setSelection({ type: 'node', id: node.id })
            setSuggestHiddenFor(null)
            setMenu({ x: event.clientX, y: event.clientY, target: { type: 'node', id: node.id } })
          }}
          onEdgeContextMenu={(event, edge) => {
            event.preventDefault()
            if (edge.id.startsWith(ghostPrefix)) { setMenu({ x: event.clientX, y: event.clientY, target: { type: 'ghost', id: edge.id } }); return }
            setSelection({ type: 'edge', key: edge.id })
            setMenu({ x: event.clientX, y: event.clientY, target: { type: 'edge', key: edge.id } })
          }}
          minZoom={.1} maxZoom={2} snapToGrid snapGrid={[10, 10]} fitView proOptions={{ hideAttribution: true }}>
          <Background variant={BackgroundVariant.Dots} gap={20} size={1} />
          <Controls showInteractive={false} />
          <MiniMap pannable zoomable />
          {view === 'graph' && detail && detailNode && <FlowNodeTooltip anchor={detail.anchor} data={detailNode.data} />}
          {allSuggestions && (allSuggestions.next.length > 0 || allSuggestions.previous.length > 0) && <Panel position="top-center" className="game-flow-hint">
            {ghosts ? <>
              {ghosts.next.length > 0 && <span><kbd>Tab</kbd> 次の候補 {ghosts.next.length} 件を配置</span>}
              {ghosts.previous.length > 0 && <span><kbd>Shift</kbd>+<kbd>Tab</kbd> 入手元・前 {ghosts.previous.length} 件</span>}
              <span>候補をクリックで1件ずつ</span><span><kbd>Esc</kbd> 隠す</span>
            </> : <button type="button" className="button compact subtle" onClick={() => setSuggestHiddenFor(null)}>候補を表示</button>}
          </Panel>}
        </ReactFlow> : <div className="game-flow-empty">{loading ? '読み込んでいます…' : '「一覧」からフローを開くか、新規作成してください。'}</div>}
      </div>
      {view === 'text' && <div className="game-flow-text" onContextMenu={onCopyContextMenu({
        ...currentFileTarget, values: [...currentFileTarget.values, { label: 'テキストをコピー', value: outline }],
      }, 'フローのテキスト')}>
        <div className="game-flow-text-head"><span>参照先の名前を解決した一覧です。AI へ渡すときはこのテキストか YAML を使います。</span>
          <button type="button" className="button compact" disabled={!outline} onClick={() => copy(outline)}>コピー</button></div>
        <pre>{outline}</pre>
      </div>}
      {view === 'yaml' && <div className="game-flow-text" onContextMenu={onCopyContextMenu({
        ...currentFileTarget, values: [...currentFileTarget.values, { label: 'YAMLをコピー', value: yaml }],
      }, 'フローのYAML')}>
        <div className="game-flow-text-head"><span>{dirty ? '保存前の原稿のプレビューです。' : '保存済みの原稿です。'}{document && <code> 40_filebase/{document.path}</code>}</span>
          <button type="button" className="button compact" disabled={!yaml} onClick={() => copy(yaml)}>コピー</button></div>
        <pre>{yaml}</pre>
      </div>}
    </section>

    <aside id={inspectorId} className="game-flow-inspector" aria-label="プロパティ" hidden={!inspectorOpen}>
      {flow && !selectedNode && !selectedEdge && <FlowProperties flow={flow} className={className} path={document?.path}
        resolve={resolve} copyContextMenu={onCopyContextMenu} onEditClasses={() => setClassPopoverOpen(true)}
        onChange={change => update(current => ({ ...current, ...change }))} />}
      {flow && selectedNode && <NodeProperties node={selectedNode} kind={nodeKind(selectedNode, prefixes, selectedNode.ref ? resolve(selectedNode.ref)?.file?.path : undefined)} resolved={selectedNode.ref ? resolve(selectedNode.ref) : undefined}
        classes={flowClasses} context={context} flow={flow} resolveName={resolveName} copyContextMenu={onCopyContextMenu}
        onChange={change => changeNode(selectedNode.id, change)} onPick={kind => setPicker({ kind: kind === 'npc' ? 'mob' : kind, replace: selectedNode.id })}
        onOpenMaster={onOpenMaster} onShopItems={() => setShopDialog(selectedNode.id)}
        suggestions={allSuggestions}
        resolve={resolve} prefixes={prefixes} onAccept={list => placeSuggestions(selectedNode.id,
          list.map(entry => ghosts?.[entry.direction].find(shown => shown.ref === entry.ref) ?? entry))}
        onDelete={() => { update(current => removeNodes(current, new Set([selectedNode.id]))); setSelection(null) }} />}
      {flow && selectedEdge && <EdgeProperties edge={selectedEdge} flow={flow} nodeName={nodeName} classes={flowClasses} resolve={resolve} copyContextMenu={onCopyContextMenu}
        onChange={change => changeEdge(edgeKey(selectedEdge), change)}
        onDelete={() => { update(current => ({ ...current, edges: current.edges.filter(edge => edge !== selectedEdge) })); setSelection(null) }} />}
      {!flow && <p className="game-flow-muted">フローを開くと、ノード・矢印・フロー全体の設定を編集できます。</p>}
      <details className="game-flow-help"><summary>操作方法</summary>
        <ul>
          <li>ツールバーの「追加」にある「ダンジョン」などからマスターを参照するノードを追加します。</li>
          <li>ノードにポインターを重ねると上下左右に接続点が出ます。始点の接続点から終点の接続点へドラッグすると、その向きに矢印がつながります。</li>
          <li>ポインターを重ねるか、Tab キーでノードにフォーカスすると詳細が表示されます。</li>
          <li>「操作」はマスター参照のない手順を表します。「メモ」は図に配置する付箋です。</li>
          <li>ショップのノードを選ぶと、商品を購入アイテムとしてまとめて追加できます。</li>
          <li>フロー全体の対象職業はツールバーで設定します。職業で絞り込むと、該当しないノードや矢印が薄く表示されます。</li>
          <li>ノードや矢印ごとの対象職業は、選択してプロパティで設定します。</li>
          <li>「一覧」「プロパティ」で左右のパネルを開閉します。図にフォーカスがあるときは Ctrl+[ で一覧、Ctrl+] でプロパティを開閉できます。</li>
          <li>Delete キーで選択中のノード・矢印を削除します。</li>
          <li>図・ノード・矢印を右クリックすると、その場所への追加や候補の配置などの操作を選べます。</li>
          <li>フロー一覧、アイコン、プロパティや候補を右クリックすると、名前・参照・ファイルパスなどをコピーできます。入力欄では文字を選択するか Shift を押しながら右クリックすると、標準の編集メニューが開きます。</li>
        </ul>
        <p className="game-flow-muted">アイコン表示の形: {shapeLegend.map(([kind, shape]) => `${kindLabels[kind] ?? kind}=${shape}`).join('、')}</p>
      </details>
    </aside>

    {picker && <MasterEntityPicker title={`${kindLabels[picker.kind] ?? picker.kind}を選択`} choices={pickerChoices}
      onChoiceContextMenu={(choice, event) => onCopyContextMenu(referenceTarget(`${picker.kind}:${choice.id}`), '候補')(event)}
      value={picker.replace ? refId(flow?.nodes.find(node => node.id === picker.replace)?.ref) : undefined}
      onSelect={selectPicked} onClose={() => setPicker(null)} />}
    {shopDialog && flow && <ShopItemsDialog document={panelDocument} flow={flow} shopNodeId={shopDialog} context={context}
      resolve={id => resolve('item:' + id)} resolveClass={resolve} className={className} copyContextMenu={onCopyContextMenu}
      isItem={(ref, id) => refKind(ref, prefixes) === 'item' && refId(ref) === id}
      onAdd={itemIds => { update(current => addShopPurchases(current, shopDialog, itemIds, context, prefixes)); setShopDialog(null) }}
      onClose={() => setShopDialog(null)} />}
    {creating && <CreateFlowDialog document={panelDocument} busy={busy} existing={new Set(flowFiles.map(file => file.path))}
      onCreate={(id, name) => void create(id, name)} onClose={() => setCreating(false)} />}
    {menu && flow && <FlowContextMenu x={menu.x} y={menu.y} onDismiss={closeMenu} {...contextCommands(menu.target)} />}
    {copyMenu}
    {confirmation}
  </div></div>
}

function CreateFlowDialog({ document, busy, existing, onCreate, onClose }: {
  document: Document; busy: boolean; existing: ReadonlySet<string>; onCreate: (id: string, name: string) => void; onClose: () => void
}) {
  const [id, setId] = useState('')
  const [name, setName] = useState('')
  const problem = !id ? 'IDを入力してください。' : !flowIdPattern.test(id) ? 'IDは英小文字・数字を _ でつないで入力してください。'
    : existing.has(gameFlowPath(id)) ? '同じIDのフローがあります。' : !name.trim() ? '表示名を入力してください。' : ''
  return createPortal(<Modal ariaLabel="新しいゲーム進行フロー" className="game-flow-dialog compact" ownerDocument={document} onClose={onClose} closeDisabled={busy}>
    <header className="modal-header"><h2>新しいゲーム進行フロー</h2></header>
    <form className="modal-body game-flow-props" onSubmit={event => { event.preventDefault(); if (!problem) onCreate(id, name.trim()) }}>
      <label>ID<input value={id} autoFocus placeholder="early_game_weapons" onChange={event => setId(event.target.value)} /></label>
      <label>表示名<input value={name} placeholder="序盤の武器更新" onChange={event => setName(event.target.value)} /></label>
      <p className="game-flow-muted">保存先: <code>40_filebase/{gameFlowPath(id || '<id>')}</code></p>
      {problem && id && <p className="game-flow-error-text">{problem}</p>}
      <footer className="modal-footer">
        <button type="button" className="button" disabled={busy} onClick={onClose}>キャンセル</button>
        <button type="submit" className="button primary" disabled={busy || Boolean(problem)}>作成</button>
      </footer>
    </form>
  </Modal>, document.body)
}
