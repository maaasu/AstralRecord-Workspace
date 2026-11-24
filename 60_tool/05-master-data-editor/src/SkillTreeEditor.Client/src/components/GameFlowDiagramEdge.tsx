import { createContext, memo, useCallback, useContext, useEffect, useId, useLayoutEffect, useMemo, useRef, useState, useSyncExternalStore, type ReactNode } from 'react'
import { BaseEdge, getSmoothStepPath, useStore, type Edge, type EdgeProps } from '@xyflow/react'
import { usePanelDocument } from '../docking/dockPanel'
import { gameFlowEdgeRoute, gameFlowGapPath, gameFlowJumpPaths, gameFlowLabelPosition, type FlowEdgeKind, type FlowEdgeRoute, type FlowJumpDisplay, type FlowJumpGap } from './gameFlowEdgeJumps'

type FlowEdge = Edge<{ kind: FlowEdgeKind }>
type EdgeSnapshot = FlowJumpDisplay & { original: string; signature: string }

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
        const signature = JSON.stringify(display)
        updated.set(id, previous?.original === original && previous.signature === signature ? previous : { original, signature, ...display })
      }
      const previous = snapshots
      snapshots = updated
      for (const [id, subscribers] of listeners) {
        if (previous.get(id) !== updated.get(id)) subscribers.forEach(listener => listener())
      }
    })
  }
  return {
    update(route: FlowEdgeRoute) {
      routes.set(route.id, route)
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

type Rect = { x: number; y: number; width: number; height: number }

const rectPath = ({ x, y, width, height }: Rect) => `M${x} ${y}h${width}v${height}h${-width}Z`

function interactionHole(gap: FlowJumpGap, strokeWidth: number) {
  const halfWidth = strokeWidth / 2 + 1
  const width = gap.end - gap.start
  return gap.axis === 'horizontal'
    ? { x: gap.start, y: gap.fixed - halfWidth, width, height: halfWidth * 2 }
    : { x: gap.fixed - halfWidth, y: gap.start, width: halfWidth * 2, height: width }
}

const GameFlowDiagramEdge = memo(function GameFlowDiagramEdge({
  id, data, sourceX, sourceY, targetX, targetY, sourcePosition, targetPosition,
  label, labelStyle, labelShowBg, labelBgStyle, labelBgPadding, labelBgBorderRadius,
  style, markerStart, markerEnd, interactionWidth,
}: EdgeProps<FlowEdge>) {
  const routes = useContext(EdgeRoutesContext)
  const [path, labelX, labelY] = getSmoothStepPath({ sourceX, sourceY, targetX, targetY, sourcePosition, targetPosition })
  const kind = data?.kind ?? 'progression'
  const route = useMemo(() => gameFlowEdgeRoute(id, path, kind), [id, kind, path])
  useLayoutEffect(() => {
    routes?.update(route)
    return () => routes?.remove(id)
  }, [id, route, routes])
  const subscribe = useCallback((listener: () => void) => routes?.subscribe(id, listener) ?? (() => undefined), [id, routes])
  const read = useCallback(() => routes?.read(id), [id, routes])
  const snapshot = useSyncExternalStore(subscribe, read)
  const display = snapshot?.original === path ? snapshot : undefined
  const group = useRef<SVGGElement>(null)
  const [labelSize, setLabelSize] = useState({ width: 0, height: 0 })
  const hasNearbyJumps = Boolean(display?.jumps?.length && label)
  useLayoutEffect(() => {
    if (!hasNearbyJumps) return
    const measured = group.current?.querySelector<SVGTextElement>('.react-flow__edge-text')?.getBBox()
    if (measured) setLabelSize(previous => previous.width === measured.width && previous.height === measured.height
      ? previous : { width: measured.width, height: measured.height })
  }, [hasNearbyJumps, label, labelStyle])
  const [paddingX, paddingY] = labelShowBg === false ? [0, 0] : labelBgPadding ?? [2, 4]
  const labelPosition = display?.jumps && labelSize.width
    ? gameFlowLabelPosition(route, { x: labelX, y: labelY }, labelSize.width + 2 * paddingX, labelSize.height + 2 * paddingY, display.jumps)
    : { x: labelX, y: labelY }
  const gaps = display?.gaps
  const maskId = `game-flow-jump-${useId()}`
  const clipId = (index: number) => `${maskId}-hit-${index}`
  const hitWidth = Math.max(2, interactionWidth ?? 20)
  // 既定の曲線が始点・終点の外へ回り込む範囲も含める。
  const padding = 64 + hitWidth / 2
  const bounds = {
    x: Math.min(sourceX, targetX) - padding, y: Math.min(sourceY, targetY) - padding,
    width: Math.abs(targetX - sourceX) + padding * 2, height: Math.abs(targetY - sourceY) + padding * 2,
  }
  const hitPath = gaps?.reduceRight<ReactNode>((child, _gap, index) =>
    <g clipPath={`url(#${clipId(index)})`}>{child}</g>,
    <path d={display?.path ?? path} fill="none" strokeOpacity={0}
      strokeWidth={hitWidth} className="react-flow__edge-interaction" />)
  return <g ref={group}>
    {gaps && <defs>
      <mask id={maskId} maskUnits="userSpaceOnUse" {...bounds}>
        <rect {...bounds} fill="white" />
        <path d={gameFlowGapPath(gaps)} fill="none" stroke="black" strokeWidth={6} style={{ strokeDasharray: 'none', animation: 'none' }} />
      </mask>
      {gaps.map((gap, index) => <clipPath key={index} id={clipId(index)} clipPathUnits="userSpaceOnUse">
        <path d={rectPath(bounds) + rectPath(interactionHole(gap, hitWidth))} clipRule="evenodd" />
      </clipPath>)}
    </defs>}
    {hitPath}
    <BaseEdge path={display?.path ?? path} mask={gaps ? `url(#${maskId})` : undefined} pointerEvents={gaps ? 'none' : undefined}
      labelX={labelPosition.x} labelY={labelPosition.y} label={label} labelStyle={labelStyle} labelShowBg={labelShowBg}
      labelBgStyle={labelBgStyle} labelBgPadding={labelBgPadding} labelBgBorderRadius={labelBgBorderRadius}
      style={style} markerStart={markerStart} markerEnd={markerEnd} interactionWidth={gaps ? 0 : interactionWidth} />
  </g>
})

export const gameFlowEdgeTypes = { flow: GameFlowDiagramEdge }
