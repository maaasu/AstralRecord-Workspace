type Point = { x: number; y: number }
type Axis = 'horizontal' | 'vertical'
type Line = { axis: Axis; fixed: number; start: number; end: number }
type PathPart = { raw: string; line?: Line }
export type FlowEdgeKind = 'progression' | 'annotation' | 'candidate'

export interface FlowEdgeRoute {
  id: string
  path: string
  kind: FlowEdgeKind
  parts: PathPart[]
}

const priority: Record<FlowEdgeKind, number> = { progression: 0, annotation: 1, candidate: 2 }

/** React Flow の角丸パスから直線だけを抽出する。曲線と元の描画命令は保持する。 */
export function gameFlowEdgeRoute(id: string, path: string, kind: FlowEdgeKind): FlowEdgeRoute {
  const parts: PathPart[] = []
  let point: Point | undefined
  for (const raw of path.match(/[MLQ][^MLQ]*/g) ?? []) {
    const coordinates = raw.slice(1).trim().split(/[\s,]+/).map(Number)
    const end = { x: coordinates.at(-2)!, y: coordinates.at(-1)! }
    let line: Line | undefined
    if (point && raw.startsWith('L')) {
      if (point.y === end.y && point.x !== end.x) {
        line = { axis: 'horizontal', fixed: point.y, start: point.x, end: end.x }
      } else if (point.x === end.x && point.y !== end.y) {
        line = { axis: 'vertical', fixed: point.x, start: point.y, end: end.y }
      }
    }
    const previous = parts.at(-1)
    // 配線の途中にある同一直線上の点は、交差を避けるべき曲がり角には含めない。
    if (line && previous?.line?.axis === line.axis && previous.line.fixed === line.fixed
      && previous.line.end === line.start && Math.sign(previous.line.end - previous.line.start) === Math.sign(line.end - line.start)) {
      previous.line.end = line.end
      previous.raw += raw
    } else if (point?.x === end.x && point.y === end.y && raw.startsWith('L') && previous) {
      previous.raw += raw
    } else {
      parts.push({ raw, line })
    }
    point = end
  }
  return { id, kind, path, parts }
}

type DrawingLine = { route: FlowEdgeRoute; line: Line; low: number; high: number }
type Crossings = Map<number, DrawingLine[]>

export interface FlowJumpDisplay {
  path: string
  gapPath?: string
}

function firstLineAfter(lines: readonly DrawingLine[], x: number) {
  let low = 0
  let high = lines.length
  while (low < high) {
    const middle = (low + high) >>> 1
    if (lines[middle].line.fixed <= x) low = middle + 1
    else high = middle
  }
  return low
}

function svgPoint(axis: Axis, fixed: number, along: number) {
  return axis === 'horizontal' ? `${along} ${fixed}` : `${fixed} ${along}`
}

function pathWithJumps(line: Line, crossings: Crossings, radius: number, mask: (lower: DrawingLine, apex: number, size: number) => void) {
  const { start, end, fixed, axis } = line
  const direction = Math.sign(end - start)
  const points = [...crossings.keys()].sort((a, b) => (a - b) * direction)
  const point = (along: number) => svgPoint(axis, fixed, along)
  let path = ''
  points.forEach((crossing, index) => {
    const before = Math.abs(crossing - (points[index - 1] ?? start))
    const after = Math.abs(crossing - (points[index + 1] ?? end))
    const size = Math.min(radius, before / 2, after / 2)
    // 近い交差も別々にまたぎ、線端やカーブは元のまま残す。
    if (size < 0.5) return
    path += `L${point(crossing - size * direction)}A${size} ${size} 0 0 ${direction > 0 ? 1 : 0} ${point(crossing + size * direction)}`
    const apex = fixed + (axis === 'horizontal' ? -size : size)
    for (const lower of crossings.get(crossing) ?? []) mask(lower, apex, size)
  })
  return path ? `${path}L${point(end)}` : undefined
}

/** 直交する線分の内側だけを調べ、片方の線に半円を足す。 */
export function gameFlowJumpPaths(routes: Iterable<FlowEdgeRoute>, radius: number) {
  const horizontal: DrawingLine[] = []
  const vertical: DrawingLine[] = []
  for (const route of routes) {
    for (const { line } of route.parts) {
      if (!line) continue
      const ordered = line.axis === 'horizontal' ? horizontal : vertical
      ordered.push({ route, line, low: Math.min(line.start, line.end), high: Math.max(line.start, line.end) })
    }
  }
  vertical.sort((a, b) => a.line.fixed - b.line.fixed)
  const crossings = new Map<FlowEdgeRoute, Map<Line, Crossings>>()
  for (const across of horizontal) {
    for (let index = firstLineAfter(vertical, across.low); index < vertical.length; index++) {
      const down = vertical[index]
      if (down.line.fixed >= across.high) break
      if (across.route === down.route || across.line.fixed <= down.low || across.line.fixed >= down.high) continue
      const bridge = priority[across.route.kind] >= priority[down.route.kind] ? across : down
      const lower = bridge === across ? down : across
      const value = lower.line.fixed
      let onRoute = crossings.get(bridge.route)
      if (!onRoute) crossings.set(bridge.route, onRoute = new Map())
      let onLine = onRoute.get(bridge.line)
      if (!onLine) onRoute.set(bridge.line, onLine = new Map())
      const underneath = onLine.get(value)
      if (underneath) underneath.push(lower)
      else onLine.set(value, [lower])
    }
  }
  const paths = new Map<string, FlowJumpDisplay>()
  const apply = (route: FlowEdgeRoute, change: Partial<FlowJumpDisplay>) => {
    paths.set(route.id, { path: route.path, ...paths.get(route.id), ...change })
  }
  // 弧の頂点で下の線と接して見えないように、下の直線区間の該当箇所だけをマスクする。
  const mask = (lower: DrawingLine, apex: number, radius: number) => {
    const size = Math.min(Math.max(2.5, radius / 3), apex - lower.low, lower.high - apex)
    if (size <= 0) return
    const { route, line } = lower
    const start = svgPoint(line.axis, line.fixed, apex - size)
    const end = svgPoint(line.axis, line.fixed, apex + size)
    apply(route, { gapPath: `${paths.get(route.id)?.gapPath ?? ''}M${start}L${end}` })
  }
  for (const [route, jumps] of crossings) {
    const rendered = route.parts.map(part => {
      const at = part.line && jumps.get(part.line)
      return (part.line && at && pathWithJumps(part.line, at, radius, mask)) || part.raw
    }).join('')
    if (rendered !== route.path) apply(route, { path: rendered })
  }
  return paths
}
