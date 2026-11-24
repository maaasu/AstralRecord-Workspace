import { useCallback, useEffect, useRef, useState, type Dispatch, type RefObject, type SetStateAction } from 'react'
import { applyNodeChanges, useReactFlow, useStoreApi, type Node, type NodeChange, type NodePositionChange, type XYPosition } from '@xyflow/react'
import type { FlowNodeData } from './GameFlowDiagramNode'
import { gameFlowSnapNeighbors, measureGameFlowShapes, snapGameFlowNode, type GameFlowShapeCenter, type GameFlowSnapGuide } from './gameFlowSnapping'

type FlowNode = Node<FlowNodeData>
type Drag = {
  anchorId: string
  shape?: GameFlowShapeCenter
  neighbors: GameFlowShapeCenter[]
  initial: Map<string, XYPosition>
  raw?: Map<string, XYPosition>
  positions?: Map<string, XYPosition>
  ending?: boolean
}

export function useGameFlowNodeDrag(canvas: RefObject<HTMLDivElement | null>, setNodes: Dispatch<SetStateAction<FlowNode[]>>,
  onDrop: (positions: ReadonlyMap<string, XYPosition>) => void) {
  const flow = useReactFlow<FlowNode>()
  const store = useStoreApi<FlowNode>()
  const drag = useRef<Drag | null>(null)
  const [guides, setGuides] = useState<GameFlowSnapGuide[]>([])

  const finish = useCallback((save: boolean) => {
    const session = drag.current
    if (!session) return
    drag.current = null
    store.setState({ snapToGrid: true })
    setGuides([])
    if (save) {
      if (session.positions) onDrop(session.positions)
    } else {
      setNodes(nodes => nodes.map(node => {
        const position = session.initial.get(node.id)
        return position ? { ...node, position, dragging: false } : node
      }))
    }
  }, [onDrop, setNodes, store])
  const cancel = useCallback(() => finish(false), [finish])
  useEffect(() => () => {
    if (!drag.current) return
    drag.current = null
    store.setState({ snapToGrid: true })
  }, [store])

  const project = useCallback((session: Drag, raw: Map<string, XYPosition>) => {
    const anchor = raw.get(session.anchorId)
    if (!anchor) return
    const { transform: [x, y, zoom], width, height } = store.getState()
    const snapped = snapGameFlowNode(anchor, session.shape, session.neighbors, { x, y, zoom, width, height })
    const dx = snapped.position.x - anchor.x
    const dy = snapped.position.y - anchor.y
    session.positions = new Map([...raw].map(([id, point]) =>
      [id, { x: Math.round(point.x + dx), y: Math.round(point.y + dy) }]))
    setGuides(snapped.guides)
  }, [store])

  useEffect(() => store.subscribe((state, previous) => {
    const session = drag.current
    if (!session?.raw || session.ending || (state.transform === previous.transform
      && state.width === previous.width && state.height === previous.height)) return
    project(session, session.raw)
    const positions = session.positions
    setNodes(nodes => nodes.map(node => {
      const position = positions?.get(node.id)
      return position ? { ...node, position } : node
    }))
  }), [project, setNodes, store])

  const onNodeDragStart = useCallback((_: unknown, anchor: FlowNode, moved: FlowNode[]) => {
    if (!canvas.current || !anchor || anchor.data.suggestion !== undefined) return
    const initial = new Map(moved.map(node => [node.id, node.position]))
    const shapes = measureGameFlowShapes(canvas.current, flow.getZoom())
    const neighbors = gameFlowSnapNeighbors(flow.getNodes(), shapes, initial)
    drag.current = { anchorId: anchor.id, shape: shapes.get(anchor.id), neighbors, initial }
    // このドラッグだけ生座標を受け取る。キーボード移動などは React Flow のグリッドを使う。
    store.setState({ snapToGrid: false })
  }, [canvas, flow, store])

  const onNodesChange = useCallback((changes: NodeChange<FlowNode>[]) => {
    const session = drag.current
    const filtered = changes.filter(change => change.type !== 'remove')
    if (!session) {
      setNodes(nodes => applyNodeChanges(filtered, nodes))
      return
    }
    const moving = changes.filter((change): change is NodePositionChange => change.type === 'position' && session.initial.has(change.id))
    const anchor = moving.find(change => change.id === session.anchorId)
    if (anchor?.dragging && anchor.position) {
      session.raw = new Map(moving.flatMap(change => change.position ? [[change.id, change.position]] : []))
      project(session, session.raw)
    } else if (anchor?.dragging === false) {
      session.ending = true
      // 通常はこの通知のすぐ後で onNodeDragStop が来る。マルチタッチ等により中断された場合は戻す。
      queueMicrotask(() => { if (drag.current === session) cancel() })
    }
    const positions = session.positions ?? session.initial
    const adjusted = filtered.map(change => {
      const position = change.type === 'position' ? positions.get(change.id) : undefined
      return position ? { ...change, position } : change
    })
    setNodes(nodes => applyNodeChanges(adjusted, nodes))
  }, [cancel, project, setNodes])

  // 終了イベントの座標は React Flow 側の生値。直前に画面へ出した位置をそのまま履歴へ記録する。
  const onNodeDragStop = useCallback(() => finish(true), [finish])
  return { guides, onNodeDragStart, onNodesChange, onNodeDragStop, cancel }
}
