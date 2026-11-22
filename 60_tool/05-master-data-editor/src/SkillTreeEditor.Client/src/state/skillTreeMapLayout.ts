export const BLOCK_SCALE = 36
export const CP_CORNER_RATIO = 0.18
export const DEFAULT_NODE_SIZE = 56
export const LABEL_HALF_WIDTH = 44
export const LABEL_HEIGHT = 30
export const LABEL_GAP = 5

type MapPoint = { x: number; z: number }
type NodePoint = MapPoint & { nodeId: string }
type MapBranch = { point: MapPoint; axis: 'x' | 'z'; low?: MapBranch; high?: MapBranch }

interface NodeSpacing {
  nearest: number
  squareLimit: number
  coincidentIds: readonly string[]
}

function spatialTree(byX: MapPoint[], byZ: MapPoint[]): MapBranch | undefined {
  if (!byX.length) return
  const middle = byX.length >> 1
  const xIsWider = byX.at(-1)!.x - byX[0].x >= byZ.at(-1)!.z - byZ[0].z
  const primary = xIsWider ? byX : byZ
  const secondary = xIsWider ? byZ : byX
  const point = primary[middle]
  const low = primary.slice(0, middle)
  const high = primary.slice(middle + 1)
  const lowPoints = new Set(low)
  const secondaryLow: MapPoint[] = [], secondaryHigh: MapPoint[] = []
  for (const candidate of secondary) {
    if (candidate !== point) (lowPoints.has(candidate) ? secondaryLow : secondaryHigh).push(candidate)
  }
  return {
    point,
    axis: xIsWider ? 'x' : 'z',
    low: xIsWider ? spatialTree(low, secondaryLow) : spatialTree(secondaryLow, low),
    high: xIsWider ? spatialTree(high, secondaryHigh) : spatialTree(secondaryHigh, high),
  }
}

export function measureNodeSpacing(nodes: readonly NodePoint[]) {
  // 同じX/Zを持つ別の高さの配置は、地図上では同じ位置として扱う。
  const keyOf = ({ x, z }: MapPoint) => `${x}:${z}`
  const locations = new Map<string, { point: MapPoint; ids: string[] }>()
  for (const node of nodes) {
    const key = keyOf(node)
    const location = locations.get(key)
    if (location) location.ids.push(node.nodeId)
    else locations.set(key, { point: node, ids: [node.nodeId] })
  }
  const points = [...locations.values()].map(location => location.point)
  const tree = spatialTree(
    [...points].sort((a, b) => a.x - b.x),
    [...points].sort((a, b) => a.z - b.z),
  )
  const measurements = new Map<string, NodeSpacing>()
  for (const { point, ids } of locations.values()) {
    let nearest = Infinity
    let squareLimit = Infinity
    function visit(branch: MapBranch | undefined) {
      if (!branch) return
      const dx = point.x - branch.point.x, dz = point.z - branch.point.z
      if (dx || dz) {
        const distanceSquared = dx * dx + dz * dz
        nearest = Math.min(nearest, Math.sqrt(distanceSquared))
        // 四角の角も、相手との垂直二等分線を越えない直径。
        squareLimit = Math.min(squareLimit, distanceSquared / (Math.abs(dx) + Math.abs(dz)))
      }
      const delta = branch.axis === 'x' ? dx : dz
      visit(delta < 0 ? branch.low : branch.high)
      if (Math.abs(delta) < Math.max(nearest, squareLimit * Math.SQRT2)) {
        visit(delta < 0 ? branch.high : branch.low)
      }
    }
    visit(tree)
    const spacing = { nearest: nearest * BLOCK_SCALE, squareLimit: squareLimit * BLOCK_SCALE, coincidentIds: ids }
    for (const id of ids) measurements.set(id, spacing)
  }
  return measurements
}

export function nodeDiameter(
  maximum: number,
  spacing: NodeSpacing,
  scale: number,
  square: boolean,
): number {
  const limit = square ? spacing.squareLimit : spacing.nearest
  // 重なった配置しかない場合も、既定のサイズまでに収める。
  const sizeCap = limit === Infinity && spacing.coincidentIds.length > 1
    ? Math.min(maximum, DEFAULT_NODE_SIZE)
    : maximum
  const base = Math.min(sizeCap, spacing.nearest * 0.78)
  return Math.min(limit * 0.9, base * scale)
}

type MapFrame = { id: string; x: number; y: number; diameter: number }

export function measureLabelZoom(
  frames: readonly MapFrame[],
  spacing: ReadonlyMap<string, NodeSpacing>,
  minZoom: number,
  maxZoom: number,
): Map<string, number> {
  const cellSize = 90 / maxZoom
  const cell = (value: number) => Math.floor(value / cellSize)
  const cellKey = (x: number, y: number) => `${x}:${y}`
  const cells = new Map<string, MapFrame[]>()
  const largestAtPosition = new Map<string, MapFrame>()
  let maxRadius = 0
  for (const frame of frames) {
    const key = cellKey(frame.x, frame.y)
    if ((largestAtPosition.get(key)?.diameter ?? 0) < frame.diameter) largestAtPosition.set(key, frame)
    maxRadius = Math.max(maxRadius, frame.diameter / 2)
  }
  for (const frame of largestAtPosition.values()) {
    const key = cellKey(cell(frame.x), cell(frame.y))
    const local = cells.get(key)
    if (local) local.push(frame)
    else cells.set(key, [frame])
  }
  const clearanceZoom = (pixels: number, clearance: number) => clearance > 0 ? pixels / clearance : Infinity
  return new Map(frames.map(frame => {
    const { nearest, coincidentIds } = spacing.get(frame.id)!
    let zoom = Math.max(minZoom, 90 / nearest)
    if (coincidentIds.length > 1 || zoom > maxZoom) return [frame.id, Infinity]

    const bottom = frame.y + frame.diameter / 2
    const reachX = maxRadius + LABEL_HALF_WIDTH / zoom
    const reachY = maxRadius + (LABEL_GAP + LABEL_HEIGHT) / zoom
    for (let x = cell(frame.x - reachX); x <= cell(frame.x + reachX); x++) {
      for (let y = cell(bottom - maxRadius); y <= cell(bottom + reachY); y++) {
        for (const other of cells.get(cellKey(x, y)) ?? []) {
          const radius = other.diameter / 2
          if (other.id === frame.id || other.y + radius <= bottom) continue
          const horizontal = clearanceZoom(LABEL_HALF_WIDTH, Math.abs(other.x - frame.x) - radius)
          const vertical = clearanceZoom(LABEL_GAP + LABEL_HEIGHT, other.y - radius - bottom)
          // どちらかの方向で枠と離れれば、ラベルを表示できる。
          zoom = Math.max(zoom, Math.min(horizontal, vertical))
        }
      }
    }
    return [frame.id, zoom]
  }))
}

type EdgeEnd = { x: number; y: number; diameter: number; square: boolean }

export function clippedEdgePath(first: EdgeEnd, second: EdgeEnd): string {
  const dx = second.x - first.x, dy = second.y - first.y
  const distance = Math.hypot(dx, dy)
  if (!distance) return ''
  const ux = dx / distance, uy = dy / distance
  const a = frameRadius(first, ux, uy), b = frameRadius(second, ux, uy)
  if (a + b >= distance) return ''
  return `M ${first.x + a * ux},${first.y + a * uy} L ${second.x - b * ux},${second.y - b * uy}`
}

function frameRadius(node: EdgeEnd, ux: number, uy: number): number {
  const half = node.diameter / 2
  if (!node.square) return half
  const corner = node.diameter * CP_CORNER_RATIO
  const straight = half - corner
  const side = half / Math.max(Math.abs(ux), Math.abs(uy))
  if (side * Math.min(Math.abs(ux), Math.abs(uy)) <= straight) return side
  // 角の円弧と、中心を出る単位ベクトルとの交点。
  const dot = (Math.abs(ux) + Math.abs(uy)) * straight
  return dot + Math.sqrt(Math.max(0, dot * dot - 2 * straight * straight + corner * corner))
}
