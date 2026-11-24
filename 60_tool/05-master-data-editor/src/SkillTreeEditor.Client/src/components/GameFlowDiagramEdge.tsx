import { createContext, memo, useCallback, useContext, useEffect, useId, useLayoutEffect, useMemo, useSyncExternalStore, type ReactNode } from 'react'
import { BaseEdge, getSmoothStepPath, useStore, type Edge, type EdgeProps } from '@xyflow/react'
import { usePanelDocument } from '../docking/dockPanel'
import { gameFlowEdgeRoute, gameFlowJumpPaths, type FlowEdgeKind, type FlowEdgeRoute, type FlowJumpDisplay } from './gameFlowEdgeJumps'

type FlowEdge = Edge<{ kind: FlowEdgeKind }>
type EdgeSnapshot = FlowJumpDisplay & { original: string }

/** 各エッジが React Flow から受け取った座標を共有し、1フレームにつき1回だけ交差を求める。 */
function edgeRoutes(window: Window) {
  const routes = new Map<string, FlowEdgeRoute>()
  const listeners = new Map<string, Set<() => void>>()
  let snapshots = new Map<string, EdgeSnapshot>()
  let frame: number | undefined
  let radius = 8

  const schedule = () => {
    if (frame !== undefined) return
    frame = window.requestAnimationFrame(() => {
      frame = undefined
      const updated = new Map<string, EdgeSnapshot>()
      for (const [id, display] of gameFlowJumpPaths(routes.values(), radius)) {
        const original = routes.get(id)!.path
        const previous = snapshots.get(id)
        const unchanged = previous?.original === original && previous.path === display.path && previous.gapPath === display.gapPath
        updated.set(id, unchanged ? previous : { original, ...display })
      }
      const previous = snapshots
      snapshots = updated
      for (const [id, subscribers] of listeners) {
        if (previous.get(id) !== updated.get(id)) subscribers.forEach(listener => listener())
      }
    })
  }
  return {
    update(id: string, path: string, kind: FlowEdgeKind) {
      routes.set(id, gameFlowEdgeRoute(id, path, kind))
      schedule()
    },
    remove(id: string) {
      routes.delete(id)
      schedule()
    },
    resize(nextRadius: number) {
      radius = nextRadius
      schedule()
    },
    read: (id: string) => snapshots.get(id),
    subscribe(id: string, listener: () => void) {
      const subscribers = listeners.get(id) ?? new Set()
      subscribers.add(listener)
      listeners.set(id, subscribers)
      return () => {
        subscribers.delete(listener)
        if (subscribers.size === 0) listeners.delete(id)
      }
    },
    cancel() {
      if (frame === undefined) return
      window.cancelAnimationFrame(frame)
      frame = undefined
    },
  }
}

const EdgeRoutesContext = createContext<ReturnType<typeof edgeRoutes> | null>(null)

export function GameFlowEdgeLayer({ children }: { children: ReactNode }) {
  const window = usePanelDocument().defaultView!
  const routes = useMemo(() => edgeRoutes(window), [window])
  const radius = useStore(state => Math.max(6, Math.min(14, Math.round(5 / state.transform[2]))))
  useLayoutEffect(() => routes.resize(radius), [radius, routes])
  useEffect(() => routes.cancel, [routes])
  return <EdgeRoutesContext.Provider value={routes}>{children}</EdgeRoutesContext.Provider>
}

const GameFlowDiagramEdge = memo(function GameFlowDiagramEdge({
  id, data, sourceX, sourceY, targetX, targetY, sourcePosition, targetPosition,
  label, labelStyle, labelShowBg, labelBgStyle, labelBgPadding, labelBgBorderRadius,
  style, markerStart, markerEnd, interactionWidth,
}: EdgeProps<FlowEdge>) {
  const routes = useContext(EdgeRoutesContext)
  const [path, labelX, labelY] = getSmoothStepPath({ sourceX, sourceY, targetX, targetY, sourcePosition, targetPosition })
  const kind = data?.kind ?? 'progression'
  useLayoutEffect(() => {
    routes?.update(id, path, kind)
    return () => routes?.remove(id)
  }, [id, kind, path, routes])
  const subscribe = useCallback((listener: () => void) => routes?.subscribe(id, listener) ?? (() => undefined), [id, routes])
  const read = useCallback(() => routes?.read(id), [id, routes])
  const snapshot = useSyncExternalStore(subscribe, read)
  const display = snapshot?.original === path ? snapshot : undefined
  const maskId = `game-flow-jump-${useId()}`
  // 既定の曲線が始点・終点の外へ回り込む範囲も含める。
  const padding = 64
  const bounds = {
    x: Math.min(sourceX, targetX) - padding, y: Math.min(sourceY, targetY) - padding,
    width: Math.abs(targetX - sourceX) + padding * 2, height: Math.abs(targetY - sourceY) + padding * 2,
  }
  return <>
    {display?.gapPath && <defs>
      <mask id={maskId} maskUnits="userSpaceOnUse" {...bounds}>
        <rect {...bounds} fill="white" />
        <path d={display.gapPath} fill="none" stroke="black" strokeWidth={6} />
      </mask>
    </defs>}
    <BaseEdge path={display?.path ?? path} mask={display?.gapPath ? `url(#${maskId})` : undefined}
      labelX={labelX} labelY={labelY} label={label} labelStyle={labelStyle} labelShowBg={labelShowBg}
      labelBgStyle={labelBgStyle} labelBgPadding={labelBgPadding} labelBgBorderRadius={labelBgBorderRadius}
      style={style} markerStart={markerStart} markerEnd={markerEnd} interactionWidth={interactionWidth} />
  </>
})

export const gameFlowEdgeTypes = { flow: GameFlowDiagramEdge }
