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
const lineEndPadding = 0.5

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
export type FlowJumpGap = { axis: Axis; fixed: number; start: number; end: number }
type JumpBounds = { left: number; top: number; right: number; bottom: number }

export interface FlowJumpDisplay {
  path: string
  gaps?: FlowJumpGap[]
  jumps?: JumpBounds[]
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

export function gameFlowGapPath(gaps: readonly FlowJumpGap[]) {
  return gaps.map(({ axis, fixed, start, end }) => `M${svgPoint(axis, fixed, start)}L${svgPoint(axis, fixed, end)}`).join('')
}

function roomForApex(lines: readonly DrawingLine[], crossing: number, side: number) {
  const distance = lines.reduce((minimum, line) => Math.min(minimum, side < 0 ? crossing - line.low : line.high - crossing), Infinity)
  // 最も近い線端と弧の頂点の間に、下線を切り離す空間を残す。
  return distance - Math.min(2.5, distance / 2) - lineEndPadding
}

function pathWithJumps(line: Line, crossings: Crossings, radius: number,
  register: (underneath: DrawingLine[], bounds: JumpBounds, apex: number, size: number) => void) {
  const { start, end, fixed, axis } = line
  const direction = Math.sign(end - start)
  const points = [...crossings.keys()].sort((a, b) => (a - b) * direction)
  const point = (along: number) => svgPoint(axis, fixed, along)
  let path = ''
  points.forEach((crossing, index) => {
    const before = Math.abs(crossing - (points[index - 1] ?? start))
    const after = Math.abs(crossing - (points[index + 1] ?? end))
    const limit = Math.min(radius, before / 2, after / 2)
    const underneath = crossings.get(crossing)!
    const defaultSide = axis === 'horizontal' ? -1 : 1
    const preferred = roomForApex(underneath, fixed, defaultSide)
    const opposite = roomForApex(underneath, fixed, -defaultSide)
    const preferredSide = preferred >= limit || preferred >= opposite
    const size = Math.min(limit, preferredSide ? preferred : opposite)
    // 近い交差も別々にまたぎ、線端やカーブは元のまま残す。
    if (size < 0.5) return
    const sweep = (direction > 0) === preferredSide ? 1 : 0
    path += `L${point(crossing - size * direction)}A${size} ${size} 0 0 ${sweep} ${point(crossing + size * direction)}`
    const apex = fixed + defaultSide * (preferredSide ? size : -size)
    const bounds = axis === 'horizontal'
      ? { left: crossing - size, top: Math.min(apex, fixed), right: crossing + size, bottom: Math.max(apex, fixed) }
      : { left: Math.min(apex, fixed), top: crossing - size, right: Math.max(apex, fixed), bottom: crossing + size }
    register(underneath, bounds, apex, size)
  })
  return path ? `${path}L${point(end)}` : undefined
}

/** 弧と重なるラベルだけを、最も近い交差のない位置へ移す。 */
export function gameFlowLabelPosition(route: FlowEdgeRoute, position: Point, width: number, height: number, jumps: readonly JumpBounds[]) {
  const halfWidth = width / 2 + 3
  const halfHeight = height / 2 + 3
  const overlaps = ({ x, y }: Point) => jumps.some(jump => x + halfWidth > jump.left && x - halfWidth < jump.right
    && y + halfHeight > jump.top && y - halfHeight < jump.bottom)
  if (!overlaps(position)) return position
  const candidates = jumps.flatMap(({ left, top, right, bottom }) => [
    { x: position.x, y: top - halfHeight }, { x: position.x, y: bottom + halfHeight },
    { x: left - halfWidth, y: position.y }, { x: right + halfWidth, y: position.y },
  ])
  const distance = ({ x, y }: Point) => Math.abs(x - position.x) + Math.abs(y - position.y)
  const onSegment = (point: Point, line: Line, margin = 0) => {
    const horizontal = line.axis === 'horizontal'
    const fixed = horizontal ? point.y : point.x
    const varying = horizontal ? point.x : point.y
    return Math.abs(fixed - line.fixed) < 0.01 && varying - margin >= Math.min(line.start, line.end)
      && varying + margin <= Math.max(line.start, line.end)
  }
  const clean = candidates.sort((a, b) => distance(a) - distance(b)).filter(candidate => !overlaps(candidate))
  const aligned = route.parts.flatMap(({ line }) => line && onSegment(position, line) ? [line] : [])
  return clean.find(candidate => aligned.some(line => onSegment(candidate, line, line.axis === 'horizontal' ? halfWidth : halfHeight)))
    ?? clean[0] ?? position
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
  const recordJump = (route: FlowEdgeRoute, bounds: JumpBounds) => {
    const jumps = paths.get(route.id)?.jumps
    if (jumps) jumps.push(bounds)
    else apply(route, { jumps: [bounds] })
  }
  // 弧の頂点で下の線と接して見えないように、下の直線区間の該当箇所だけをマスクする。
  const mask = (lower: DrawingLine, apex: number, radius: number) => {
    const size = Math.min(Math.max(2.5, radius / 3), apex - lower.low - lineEndPadding, lower.high - apex - lineEndPadding)
    if (size <= 0) return
    const { route, line } = lower
    const gap = { axis: line.axis, fixed: line.fixed, start: apex - size, end: apex + size }
    const gaps = paths.get(route.id)?.gaps
    if (gaps) gaps.push(gap)
    else apply(route, { gaps: [gap] })
  }
  for (const [route, jumps] of crossings) {
    const register = (underneath: DrawingLine[], bounds: JumpBounds, apex: number, size: number) => {
      recordJump(route, bounds)
      for (const lower of underneath) {
        recordJump(lower.route, bounds)
        mask(lower, apex, size)
      }
    }
    const rendered = route.parts.map(part => {
      const at = part.line && jumps.get(part.line)
      return (part.line && at && pathWithJumps(part.line, at, radius, register)) || part.raw
    }).join('')
    if (rendered !== route.path) apply(route, { path: rendered })
  }
  return paths
}
