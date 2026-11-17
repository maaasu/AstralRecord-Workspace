import { memo, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import {
  Background, BackgroundVariant, Controls, Handle, MarkerType, MiniMap, Panel, Position, ReactFlow, ReactFlowProvider,
  applyEdgeChanges, applyNodeChanges, useReactFlow,
  type Connection, type Edge, type EdgeChange, type Node, type NodeChange, type NodeProps,
} from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import { ApiError } from '../api/editorApi'
import { gameFlowApi } from '../api/gameFlowApi'
import { masterDataApi } from '../api/masterDataApi'
import {
  addNode, addShopPurchases, addableKinds, applySuggestions, connect, planSuggestions, edgeKey, edgeLabelSuggestions, emptyGameFlow, flowIdPattern,
  gameFlowCategory, gameFlowOutline, gameFlowPath, kindLabels, parseGameFlow, refId, refKind, removeNodes,
  serializeGameFlow, suggestFor, suggestedClasses, suggestionBatchSize, visibleFlow,
  type FlowSuggestion, type GameFlow, type GameFlowContext, type GameFlowEdge, type GameFlowNode,
} from '../data/gameFlow'
import { usePanelDocument } from '../docking/dockPanel'
import type { ValidationIssue } from '../types/editor'
import type { MasterDocument, MasterFileSummary, MasterReference } from '../types/masterData'
import { stripMinecraftFormatting } from '../utils/minecraft'
import { useConfirmationDialog } from './ConfirmationDialog'
import { MasterEntityPicker, type MasterEntityPickerChoice } from './MasterEntityPicker'
import { ReferenceIcon, referenceSymbol } from './MasterReferenceCollection'
import { MinecraftText } from './MinecraftText'
import { Modal } from './Modal'
import './gameFlow.css'

interface Props {
  active?: boolean
  onDirtyChange?: (dirty: boolean) => void
  onOpenMaster: (path: string) => void
}

interface ResolvedRef { name: string; file?: MasterFileSummary }

type Selection = { type: 'node'; id: string } | { type: 'edge'; key: string } | null
type PickerRequest = { kind: string; replace?: string }

interface FlowNodeData extends Record<string, unknown> {
  name: string
  kind: string
  file?: MasterFileSummary
  classNames: string[]
  note?: string
  dimmed: boolean
  missing: boolean
  /** 未確定の候補として表示する場合の、つながりの種類（ドロップ・出現など）。 */
  suggestion?: string
}

const ghostPrefix = 'ghost:'

const errorMessage = (reason: unknown) => reason instanceof Error ? reason.message : String(reason)
const plain = (value: string) => stripMinecraftFormatting(value).trim()

const FlowNodeView = memo(function FlowNodeView({ data, selected }: NodeProps<Node<FlowNodeData>>) {
  return <div className={`game-flow-node${selected ? ' selected' : ''}${data.dimmed ? ' dimmed' : ''}${data.missing ? ' missing' : ''}${data.suggestion ? ' ghost' : ''}`}
    data-kind={data.kind} title={data.suggestion ? 'クリックでこの候補を配置' : undefined}>
    <Handle type="target" position={Position.Left} />
    <div className="game-flow-node-head">
      {data.kind === 'note' ? <span className="game-flow-node-symbol" aria-hidden="true">✎</span> : <ReferenceIcon file={data.file} kind={data.kind} />}
      <span className="game-flow-node-kind">{kindLabels[data.kind] ?? data.kind}</span>
      {data.suggestion && <span className="game-flow-ghost-badge">＋ {data.suggestion}</span>}
    </div>
    <strong className="game-flow-node-name"><MinecraftText value={data.name} /></strong>
    {data.missing && <small className="game-flow-node-warning">参照先が見つかりません</small>}
    {data.classNames.length > 0 && <div className="game-flow-chips">{data.classNames.map(name => <span key={name} className="game-flow-chip">{name}</span>)}</div>}
    {data.note && <p className="game-flow-node-note">{data.note}</p>}
    <Handle type="source" position={Position.Right} />
  </div>
})

const nodeTypes = { flow: FlowNodeView }

export function GameFlowWorkspace(props: Props) {
  return <ReactFlowProvider><GameFlowEditor {...props} /></ReactFlowProvider>
}

function GameFlowEditor({ active = true, onDirtyChange, onOpenMaster }: Props) {
  const panelDocument = usePanelDocument()
  const flowApi = useReactFlow()
  const canvas = useRef<HTMLDivElement>(null)
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
  const [yaml, setYaml] = useState('')

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
  }, [flowApi])

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
  const derivedNodes = useMemo<Node<FlowNodeData>[]>(() => flow?.nodes.map(node => {
    const kind = refKind(node.ref, prefixes)
    const resolved = node.ref ? resolve(node.ref) : undefined
    return {
      id: node.id, type: 'flow', position: { x: node.x, y: node.y },
      selected: selection?.type === 'node' && selection.id === node.id,
      data: {
        name: nodeName(node), kind, file: resolved?.file, classNames: node.classes.map(className),
        note: node.note?.trim() || undefined, dimmed: !visible?.nodes.has(node.id), missing: Boolean(node.ref && !resolved),
      },
    }
  }) ?? [], [className, flow, nodeName, prefixes, resolve, selection, visible])
  const derivedEdges = useMemo<Edge[]>(() => flow?.edges.map((edge, index) => {
    const key = edgeKey(edge)
    const chips = edge.classes.length ? ` [${edge.classes.map(className).join('・')}]` : ''
    const shown = visible?.edges.has(index) ?? true
    return {
      id: key, source: edge.from, target: edge.to, label: (edge.label ?? '') + chips || undefined,
      selected: selection?.type === 'edge' && selection.key === key,
      markerEnd: { type: MarkerType.ArrowClosed, width: 18, height: 18 },
      className: shown ? undefined : 'dimmed', labelBgPadding: [6, 3] as [number, number], labelBgBorderRadius: 4,
    }
  }) ?? [], [className, flow, selection, visible])
  const [nodes, setNodes] = useState<Node<FlowNodeData>[]>([])
  const [edges, setEdges] = useState<Edge[]>([])
  // 測定済みの寸法を引き継ぎ、編集のたびに全ノードを測り直さない。
  useEffect(() => setNodes(previous => {
    const measured = new Map(previous.map(node => [node.id, node.measured]))
    return derivedNodes.map(node => measured.get(node.id) ? { ...node, measured: measured.get(node.id) } : node)
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
          name: resolved?.name ?? node.ref ?? node.id, kind: refKind(node.ref, prefixes), file: resolved?.file,
          classNames: node.classes.map(className), dimmed: false, missing: !resolved, suggestion: labels,
        },
      }
    })
    const ghostEdges: Edge[] = preview.flow.edges.slice(flow.edges.length).map(edge => ({
      id: ghostPrefix + edgeKey(edge), source: ghostId(edge.from), target: ghostId(edge.to), label: edge.label,
      className: 'ghost', animated: true, selectable: false, focusable: false,
      markerEnd: { type: MarkerType.ArrowClosed, width: 18, height: 18 }, labelBgPadding: [6, 3] as [number, number], labelBgBorderRadius: 4,
    }))
    return {
      nodes: ghostNodes, edges: ghostEdges, byGhostId,
      next: positioned.filter(entry => entry.direction === 'next'), previous: positioned.filter(entry => entry.direction === 'previous'),
    }
  }, [className, context, flow, newNodeClasses, prefixes, resolve, suggestionNodeId, suggestions])
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
  const onConnect = useCallback((connection: Connection) => {
    if (!connection.source || !connection.target) return
    update(current => {
      const from = current.nodes.find(node => node.id === connection.source)
      const label = from && refKind(from.ref, prefixes) === 'shop' ? '購入' : undefined
      return connect(current, connection.source, connection.target, label, classFilter ? [classFilter] : [])
    })
    setSelection({ type: 'edge', key: `${connection.source}->${connection.target}` })
  }, [classFilter, prefixes, update])
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
    return { x: Math.round(point.x - 110 + offset), y: Math.round(point.y - 40 + offset) }
  }
  const addReference = (kind: string, id: string) => {
    if (!current.current) return
    const result = addNode(current.current, {
      ref: `${kind}:${id}`, ...centerPosition(),
      classes: kind === 'item' ? suggestedClasses(context, id) : classFilter ? [classFilter] : [],
    })
    update(() => result.flow)
    setSelection({ type: 'node', id: result.id })
  }
  const addNote = () => {
    if (!current.current) return
    const result = addNode(current.current, { label: 'メモ', ...centerPosition(), classes: classFilter ? [classFilter] : [] })
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
    else if (picker) addReference(picker.kind, id)
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
  const outline = useMemo(() => flow ? gameFlowOutline(flow, resolveName, { prefixes, classRef: classFilter, className }) : '', [className, classFilter, flow, prefixes, resolveName])
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
  const copy = (value: string) => {
    void panelDocument.defaultView?.navigator.clipboard.writeText(value)
      .then(() => setMessage('クリップボードにコピーしました。'), () => setError('クリップボードにコピーできませんでした。'))
  }

  const selectedNode = selection?.type === 'node' ? flow?.nodes.find(node => node.id === selection.id) : undefined
  const selectedEdge = selection?.type === 'edge' ? flow?.edges.find(edge => edgeKey(edge) === selection.key) : undefined

  return <div className="game-flow-host"><div className="game-flow">
    <aside className="game-flow-sidebar" aria-label="フロー一覧">
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
          disabled={busy} onClick={() => file.path !== document?.path && void openPath(file.path)}>
          <MinecraftText value={file.name || file.id || file.path} />
          <small>{file.path.slice(gameFlowCategory.length + 1)}</small>
        </button>)}
        {!loading && !flowFiles.length && <p className="game-flow-muted">フローはまだありません。「＋ 新規」から作成します。</p>}
      </nav>
      <p className="game-flow-muted game-flow-location">保存先: <code>40_filebase/{gameFlowCategory}/</code></p>
    </aside>

    <section className="game-flow-main">
      <div className="game-flow-toolbar">
        <div className="game-flow-toolbar-group" role="group" aria-label="追加">
          {addableKinds.map(kind => <button key={kind} type="button" className="button compact" disabled={!flow || busy}
            onClick={() => setPicker({ kind })}>＋ {kindLabels[kind]}</button>)}
          <button type="button" className="button compact" disabled={!flow || busy} onClick={addNote}>＋ メモ</button>
        </div>
        <div className="game-flow-toolbar-group">
          <label className="game-flow-filter">職業
            <select value={classFilter ?? ''} disabled={!flow} onChange={event => setClassFilter(event.target.value || null)}>
              <option value="">すべて</option>
              {flowClasses.map(entry => <option key={entry.ref} value={entry.ref}>{entry.name}</option>)}
            </select>
          </label>
          <div className="game-flow-tabs" role="tablist" aria-label="表示">
            {([['graph', '図'], ['text', 'テキスト'], ['yaml', 'YAML']] as const).map(([id, label]) =>
              <button key={id} type="button" role="tab" aria-selected={view === id} className="button compact" onClick={() => setView(id)}>{label}</button>)}
          </div>
          <button type="button" className="button compact" aria-pressed={suggestOn} disabled={!flow}
            title="選んだノードから、次に置くマスター（ドロップ・出現モブなど）を候補として表示します"
            onClick={() => { setSuggestOn(value => !value); setSuggestHiddenFor(null) }}>候補 {suggestOn ? 'ON' : 'OFF'}</button>
          <button type="button" className="button compact" disabled={!past.length} title="Ctrl+Z" onClick={undo}>元に戻す</button>
          <button type="button" className="button compact" disabled={!future.length} title="Ctrl+Y" onClick={redo}>やり直す</button>
          <button type="button" className="button compact" disabled={busy || loading} onClick={() => void reloadAll()}>再読込</button>
          <button type="button" className="button compact" disabled={!dirty || busy} onClick={() => void revert()}>破棄</button>
          <button type="button" className="button compact primary" disabled={!dirty || busy} onClick={() => void save()}>{busy ? '処理中…' : '保存'}</button>
        </div>
      </div>
      {(error || message) && <div className={error ? 'game-flow-error' : 'game-flow-message'} role={error ? 'alert' : 'status'}>
        {error || message}<button type="button" className="button compact subtle" onClick={() => { setError(''); setMessage('') }}>閉じる</button>
      </div>}
      {issues.length > 0 && <ul className="game-flow-issues" aria-label="検証結果">
        {issues.map((issue, index) => <li key={index} data-severity={issue.severity}><code>{issue.path || '/'}</code> {issue.message}</li>)}
      </ul>}
      {/* 削除・Undo はキャンバス内のキー操作だけで受け付ける（xyflow の全体キー監視は使わない）。 */}
      <div className="game-flow-body" ref={canvas} tabIndex={-1} hidden={view !== 'graph'} onKeyDown={event => {
        if ((event.target as HTMLElement).closest('input, textarea, select, [contenteditable="true"]')) return
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
        {flow ? <ReactFlow nodes={displayNodes} edges={displayEdges} nodeTypes={nodeTypes} onNodesChange={onNodesChange} onEdgesChange={onEdgesChange}
          onNodeDragStop={onNodeDragStop} onConnect={onConnect} deleteKeyCode={null}
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
          onPaneClick={() => setSelection(null)} minZoom={.1} maxZoom={2} snapToGrid snapGrid={[10, 10]} fitView proOptions={{ hideAttribution: true }}>
          <Background variant={BackgroundVariant.Dots} gap={20} size={1} />
          <Controls showInteractive={false} />
          <MiniMap pannable zoomable />
          {allSuggestions && (allSuggestions.next.length > 0 || allSuggestions.previous.length > 0) && <Panel position="top-center" className="game-flow-hint">
            {ghosts ? <>
              {ghosts.next.length > 0 && <span><kbd>Tab</kbd> 次の候補 {ghosts.next.length} 件を配置</span>}
              {ghosts.previous.length > 0 && <span><kbd>Shift</kbd>+<kbd>Tab</kbd> 入手元・前 {ghosts.previous.length} 件</span>}
              <span>候補をクリックで1件ずつ</span><span><kbd>Esc</kbd> 隠す</span>
            </> : <button type="button" className="button compact subtle" onClick={() => setSuggestHiddenFor(null)}>候補を表示</button>}
          </Panel>}
        </ReactFlow> : <div className="game-flow-empty">{loading ? '読み込んでいます…' : '左の一覧からフローを開くか、新規作成してください。'}</div>}
      </div>
      {view === 'text' && <div className="game-flow-text">
        <div className="game-flow-text-head"><span>参照先の名前を解決した一覧です。AI へ渡すときはこのテキストか YAML を使います。</span>
          <button type="button" className="button compact" disabled={!outline} onClick={() => copy(outline)}>コピー</button></div>
        <pre>{outline}</pre>
      </div>}
      {view === 'yaml' && <div className="game-flow-text">
        <div className="game-flow-text-head"><span>{dirty ? '保存前の原稿のプレビューです。' : '保存済みの原稿です。'}{document && <code> 40_filebase/{document.path}</code>}</span>
          <button type="button" className="button compact" disabled={!yaml} onClick={() => copy(yaml)}>コピー</button></div>
        <pre>{yaml}</pre>
      </div>}
    </section>

    <aside className="game-flow-inspector" aria-label="プロパティ">
      {flow && !selectedNode && !selectedEdge && <FlowProperties flow={flow} classes={classes} onChange={change => update(current => ({ ...current, ...change }))} />}
      {flow && selectedNode && <NodeProperties node={selectedNode} kind={refKind(selectedNode.ref, prefixes)} resolved={selectedNode.ref ? resolve(selectedNode.ref) : undefined}
        classes={flowClasses} context={context} flow={flow} resolveName={resolveName}
        onChange={change => changeNode(selectedNode.id, change)} onPick={kind => setPicker({ kind, replace: selectedNode.id })}
        onOpenMaster={onOpenMaster} onShopItems={() => setShopDialog(selectedNode.id)}
        suggestions={allSuggestions}
        resolve={resolve} prefixes={prefixes} onAccept={list => placeSuggestions(selectedNode.id,
          list.map(entry => ghosts?.[entry.direction].find(shown => shown.ref === entry.ref) ?? entry))}
        onDelete={() => { update(current => removeNodes(current, new Set([selectedNode.id]))); setSelection(null) }} />}
      {flow && selectedEdge && <EdgeProperties edge={selectedEdge} flow={flow} nodeName={nodeName} classes={flowClasses}
        onChange={change => changeEdge(edgeKey(selectedEdge), change)}
        onDelete={() => { update(current => ({ ...current, edges: current.edges.filter(edge => edge !== selectedEdge) })); setSelection(null) }} />}
      {!flow && <p className="game-flow-muted">フローを開くと、ノード・矢印・フロー全体の設定を編集できます。</p>}
      <details className="game-flow-help"><summary>操作方法</summary>
        <ul>
          <li>上の「＋ ダンジョン」などからマスターを参照するノードを追加します。</li>
          <li>ノード右端の点から別のノードの左端へドラッグすると矢印でつながります。</li>
          <li>ショップのノードを選ぶと、商品を購入アイテムとしてまとめて追加できます。</li>
          <li>職業ごとの違いは、ノードや矢印の「対象職業」で設定します。職業フィルタで該当しないものは薄く表示されます。</li>
          <li>Delete キーで選択中のノード・矢印を削除します。</li>
        </ul>
      </details>
    </aside>

    {picker && <MasterEntityPicker title={`${kindLabels[picker.kind] ?? picker.kind}を選択`} choices={pickerChoices}
      value={picker.replace ? refId(flow?.nodes.find(node => node.id === picker.replace)?.ref) : undefined}
      onSelect={selectPicked} onClose={() => setPicker(null)} />}
    {shopDialog && flow && <ShopItemsDialog document={panelDocument} flow={flow} shopNodeId={shopDialog} context={context}
      resolve={id => resolve('item:' + id)} className={className}
      isItem={(ref, id) => refKind(ref, prefixes) === 'item' && refId(ref) === id}
      onAdd={itemIds => { update(current => addShopPurchases(current, shopDialog, itemIds, context, prefixes)); setShopDialog(null) }}
      onClose={() => setShopDialog(null)} />}
    {creating && <CreateFlowDialog document={panelDocument} busy={busy} existing={new Set(flowFiles.map(file => file.path))}
      onCreate={(id, name) => void create(id, name)} onClose={() => setCreating(false)} />}
    {confirmation}
  </div></div>
}

function ClassToggles({ value, classes, onChange, emptyLabel }: {
  value: string[]; classes: { ref: string; name: string }[]; onChange: (value: string[]) => void; emptyLabel: string
}) {
  const known = new Set(classes.map(entry => entry.ref))
  const unknown = value.filter(entry => !known.has(entry))
  return <div className="game-flow-class-toggles">
    {classes.map(entry => <label key={entry.ref} className="game-flow-class-toggle">
      <input type="checkbox" checked={value.includes(entry.ref)}
        onChange={event => onChange(event.target.checked ? [...value, entry.ref] : value.filter(item => item !== entry.ref))} />{entry.name}
    </label>)}
    {unknown.map(entry => <label key={entry} className="game-flow-class-toggle unknown">
      <input type="checkbox" checked onChange={() => onChange(value.filter(item => item !== entry))} />{entry}
    </label>)}
    <small className="game-flow-muted">{value.length ? `${value.length}職業` : emptyLabel}</small>
  </div>
}

function FlowProperties({ flow, classes, onChange }: {
  flow: GameFlow; classes: { ref: string; name: string }[]; onChange: (change: Partial<GameFlow>) => void
}) {
  return <section className="game-flow-props">
    <h3>フロー</h3>
    <label>ID<input value={flow.id} readOnly title="ID はファイル名と一致させるため変更できません" /></label>
    <label>表示名<input value={flow.name} onChange={event => onChange({ name: event.target.value })} /></label>
    <label>説明<textarea rows={3} value={flow.description ?? ''} onChange={event => onChange({ description: event.target.value })} /></label>
    <fieldset><legend>対象職業（職業フィルタの候補）</legend>
      <ClassToggles value={flow.classes} classes={classes} emptyLabel="未指定: 全職業" onChange={value => onChange({ classes: value })} />
    </fieldset>
    <p className="game-flow-muted">ノード {flow.nodes.length} 件 / 矢印 {flow.edges.length} 件</p>
  </section>
}

function SuggestionList({ title, items, resolve, prefixes, onAccept }: {
  title: string; items: FlowSuggestion[]; resolve: (ref: string) => ResolvedRef | undefined
  prefixes: Readonly<Record<string, string>>; onAccept: (list: FlowSuggestion[]) => void
}) {
  if (!items.length) return null
  return <div className="game-flow-suggestions">
    <div className="game-flow-suggestions-head"><strong>{title}（{items.length}）</strong>
      <button type="button" className="button compact" onClick={() => onAccept(items)}>すべて配置</button></div>
    <ul>{items.map(item => {
      const resolved = resolve(item.ref)
      const kind = refKind(item.ref, prefixes)
      return <li key={item.direction + item.ref}>
        <button type="button" className="game-flow-suggestion" title={item.existingNodeId ? '配置済みのノードへ矢印をつなぎます' : 'ノードを配置して矢印でつなぎます'} onClick={() => onAccept([item])}>
          <ReferenceIcon file={resolved?.file} kind={kind} />
          <MinecraftText value={resolved?.name ?? item.ref} />
          <small>{item.label}{item.existingNodeId ? '・接続のみ' : ''}</small>
        </button>
      </li>
    })}</ul>
  </div>
}

function NodeProperties({ node, kind, resolved, classes, context, flow, resolveName, onChange, onPick, onOpenMaster, onShopItems, onDelete,
  suggestions, resolve, prefixes, onAccept }: {
  node: GameFlowNode; kind: string; resolved?: ResolvedRef; classes: { ref: string; name: string }[]
  context: GameFlowContext | null; flow: GameFlow; resolveName: (ref: string) => string | undefined
  onChange: (change: Partial<GameFlowNode>) => void; onPick: (kind: string) => void; onOpenMaster: (path: string) => void
  onShopItems: () => void; onDelete: () => void
  suggestions: { next: FlowSuggestion[]; previous: FlowSuggestion[] } | null
  resolve: (ref: string) => ResolvedRef | undefined; prefixes: Readonly<Record<string, string>>
  onAccept: (list: FlowSuggestion[]) => void
}) {
  const shop = kind === 'shop' ? context?.shops.find(entry => entry.id === refId(node.ref)) : undefined
  const outgoing = flow.edges.filter(edge => edge.from === node.id)
  const incoming = flow.edges.filter(edge => edge.to === node.id)
  const suggestion = kind === 'item' ? suggestedClasses(context, refId(node.ref)) : []
  return <section className="game-flow-props">
    <h3>{kindLabels[kind] ?? kind}のノード</h3>
    <label>ノードID<input value={node.id} readOnly /></label>
    {node.ref ? <div className="game-flow-ref">
      <span className="game-flow-ref-name">{resolved ? <><ReferenceIcon file={resolved.file} kind={kind} /><MinecraftText value={resolved.name} /></> : <em>参照先が見つかりません</em>}</span>
      <code>{node.ref}</code>
      <div className="game-flow-actions">
        <button type="button" className="button compact" onClick={() => onPick(kind)}>参照を変更</button>
        {resolved?.file && <button type="button" className="button compact" onClick={() => onOpenMaster(resolved.file!.path)}>マスターを開く ↗</button>}
      </div>
    </div> : <p className="game-flow-muted">マスターを参照しないメモのノードです。</p>}
    <label>{node.ref ? '表示名の上書き（任意）' : '表示名'}<input value={node.label ?? ''} placeholder={node.ref ? '参照先の名前を表示' : ''} onChange={event => onChange({ label: event.target.value })} /></label>
    <label>補足<textarea rows={3} value={node.note ?? ''} onChange={event => onChange({ note: event.target.value })} /></label>
    <fieldset><legend>対象職業</legend>
      <ClassToggles value={node.classes} classes={classes} emptyLabel="未指定: 全職業共通" onChange={value => onChange({ classes: value })} />
      {suggestion.length > 0 && suggestion.join() !== node.classes.join() && <button type="button" className="button compact subtle"
        onClick={() => onChange({ classes: suggestion })}>装備条件の職業にする（{suggestion.map(ref => resolveName(ref) ?? ref).join('・')}）</button>}
    </fieldset>
    {kind === 'shop' && <div className="game-flow-actions">
      <button type="button" className="button compact primary" disabled={!shop?.items.length} onClick={onShopItems}>商品を購入アイテムとして追加…</button>
      {shop && <small className="game-flow-muted">商品 {shop.items.length} 件</small>}
    </div>}
    {suggestions && <>
      <SuggestionList title="次に置く候補" items={suggestions.next} resolve={resolve} prefixes={prefixes} onAccept={onAccept} />
      <SuggestionList title="入手元・前の候補" items={suggestions.previous} resolve={resolve} prefixes={prefixes} onAccept={onAccept} />
    </>}
    <p className="game-flow-muted">入る矢印 {incoming.length} 本 / 出る矢印 {outgoing.length} 本</p>
    <button type="button" className="button compact danger subtle" onClick={onDelete}>ノードを削除</button>
  </section>
}

function EdgeProperties({ edge, flow, nodeName, classes, onChange, onDelete }: {
  edge: GameFlowEdge; flow: GameFlow; nodeName: (node: GameFlowNode) => string; classes: { ref: string; name: string }[]
  onChange: (change: Partial<GameFlowEdge>) => void; onDelete: () => void
}) {
  const from = flow.nodes.find(node => node.id === edge.from)
  const to = flow.nodes.find(node => node.id === edge.to)
  return <section className="game-flow-props">
    <h3>矢印</h3>
    <p className="game-flow-edge-route"><MinecraftText value={from ? nodeName(from) : edge.from} /> → <MinecraftText value={to ? nodeName(to) : edge.to} /></p>
    <label>ラベル<input list="game-flow-edge-labels" value={edge.label ?? ''} onChange={event => onChange({ label: event.target.value })} /></label>
    <datalist id="game-flow-edge-labels">{edgeLabelSuggestions.map(label => <option key={label} value={label} />)}</datalist>
    <label>補足<textarea rows={3} value={edge.note ?? ''} onChange={event => onChange({ note: event.target.value })} /></label>
    <fieldset><legend>対象職業</legend>
      <ClassToggles value={edge.classes} classes={classes} emptyLabel="未指定: 全職業共通" onChange={value => onChange({ classes: value })} />
    </fieldset>
    <button type="button" className="button compact danger subtle" onClick={onDelete}>矢印を削除</button>
  </section>
}

function ShopItemsDialog({ document, flow, shopNodeId, context, resolve, className, isItem, onAdd, onClose }: {
  document: Document; flow: GameFlow; shopNodeId: string; context: GameFlowContext | null
  resolve: (itemId: string) => ResolvedRef | undefined; className: (ref: string) => string
  isItem: (ref: string | undefined, itemId: string) => boolean
  onAdd: (itemIds: string[]) => void; onClose: () => void
}) {
  const shopNode = flow.nodes.find(node => node.id === shopNodeId)
  const shop = context?.shops.find(entry => entry.id === refId(shopNode?.ref))
  const connectedRefs = useMemo(() => flow.edges.filter(edge => edge.from === shopNodeId)
    .map(edge => flow.nodes.find(node => node.id === edge.to)?.ref), [flow, shopNodeId])
  const connected = useMemo(() => new Set((shop?.items ?? []).filter(id => connectedRefs.some(ref => isItem(ref, id)))
    .map(id => 'item:' + id)), [connectedRefs, isItem, shop])
  const [query, setQuery] = useState('')
  const [chosen, setChosen] = useState<Set<string>>(new Set())
  const rows = useMemo(() => (shop?.items ?? []).map(id => {
    const resolved = resolve(id)
    return { id, name: resolved?.name ?? id, file: resolved?.file, classes: suggestedClasses(context, id), done: connected.has('item:' + id) }
  }).filter(row => !query.trim() || plain(row.name).includes(query.trim()) || row.id.includes(query.trim())), [connected, context, query, resolve, shop])
  const selectable = rows.filter(row => !row.done)
  const toggle = (id: string, on: boolean) => setChosen(current => {
    const next = new Set(current)
    if (on) next.add(id)
    else next.delete(id)
    return next
  })
  return createPortal(<Modal ariaLabel="商品を購入アイテムとして追加" className="game-flow-dialog" ownerDocument={document} onClose={onClose}>
    <header className="modal-header"><h2>商品を購入アイテムとして追加</h2></header>
    <div className="modal-body">
      <p className="game-flow-muted">選んだ商品をアイテムのノードとして追加し、ショップから「購入」の矢印でつなぎます。装備条件に職業がある商品は、その職業を対象職業に設定します。</p>
      <div className="game-flow-dialog-tools">
        <input type="search" placeholder="名前・IDで絞り込み" value={query} onChange={event => setQuery(event.target.value)} aria-label="商品を絞り込み" />
        <button type="button" className="button compact" onClick={() => setChosen(new Set(selectable.map(row => row.id)))}>表示中をすべて選択</button>
        <button type="button" className="button compact subtle" onClick={() => setChosen(new Set())}>選択を解除</button>
      </div>
      <ul className="game-flow-shop-items">
        {rows.map(row => <li key={row.id}><label className={row.done ? 'done' : undefined}>
          <input type="checkbox" disabled={row.done} checked={row.done || chosen.has(row.id)} onChange={event => toggle(row.id, event.target.checked)} />
          <ReferenceIcon file={row.file} kind="item" /><MinecraftText value={row.name} /><code>{row.id}</code>
          {row.classes.length > 0 && <span className="game-flow-chips">{row.classes.map(ref => <span key={ref} className="game-flow-chip">{className(ref)}</span>)}</span>}
          {row.done && <small>追加済み</small>}
        </label></li>)}
        {!rows.length && <li className="game-flow-muted">該当する商品がありません。</li>}
      </ul>
    </div>
    <footer className="modal-footer">
      <button type="button" className="button" onClick={onClose}>キャンセル</button>
      <button type="button" className="button primary" disabled={!chosen.size} onClick={() => onAdd((shop?.items ?? []).filter(id => chosen.has(id)))}>{chosen.size}件を追加</button>
    </footer>
  </Modal>, document.body)
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
