import type { XYPosition } from '@xyflow/react'

type Axis = 'x' | 'y'

export type GameFlowShapeCenter = XYPosition & { halfWidth: number; halfHeight: number }
export type GameFlowSnapGuide = { axis: Axis; value: number; from: number; to: number }
type View = { x: number; y: number; zoom: number; width: number; height: number }
type Correction = { value: number; distance: number; guide: GameFlowSnapGuide }
type Neighbors = { before?: GameFlowShapeCenter; after?: GameFlowShapeCenter }

const snapPixels = 8
const gridSize = 10

export function measureGameFlowShapes(canvas: HTMLElement, zoom: number) {
  const shapes = new Map<string, GameFlowShapeCenter>()
  for (const node of canvas.querySelectorAll<HTMLElement>('.react-flow__node[data-id]')) {
    const id = node.dataset.id
    const shape = node.querySelector<HTMLElement>('.game-flow-token-shape')?.getBoundingClientRect()
    if (!id || !shape?.width || !shape.height) continue
    const box = node.getBoundingClientRect()
    const halfWidth = shape.width / zoom / 2
    const halfHeight = shape.height / zoom / 2
    shapes.set(id, { x: (shape.left - box.left) / zoom + halfWidth, y: (shape.top - box.top) / zoom + halfHeight, halfWidth, halfHeight })
  }
  return shapes
}

export function gameFlowSnapNeighbors(nodes: readonly { id: string; position: XYPosition; hidden?: boolean; data: { suggestion?: string } }[],
  shapes: ReadonlyMap<string, GameFlowShapeCenter>, moving: ReadonlyMap<string, XYPosition>) {
  return nodes.flatMap(node => {
    const shape = shapes.get(node.id)
    if (!shape || node.hidden || node.data.suggestion !== undefined || moving.has(node.id)) return []
    return [{ ...shape, x: node.position.x + shape.x, y: node.position.y + shape.y }]
  })
}

function closestOnEachSide(pair: Neighbors, neighbor: GameFlowShapeCenter, axis: Axis, origin: number) {
  const direction = neighbor[axis] < origin ? 'before' : 'after'
  const previous = pair[direction]
  if (!previous || Math.abs(neighbor[axis] - origin) < Math.abs(previous[axis] - origin)) pair[direction] = neighbor
}

function nearestCorrection(point: GameFlowShapeCenter, neighbors: readonly GameFlowShapeCenter[], axis: Axis, radius: number) {
  const across = axis === 'x' ? 'y' : 'x'
  const span = axis === 'x' ? 'halfWidth' : 'halfHeight'
  const acrossSpan = axis === 'x' ? 'halfHeight' : 'halfWidth'
  const aligned: Neighbors = {}
  const straddling: Neighbors = {}
  let best: Correction | undefined
  const consider = (value: number, distance: number, guide: GameFlowSnapGuide) => {
    const shift = Math.abs(value - point[axis])
    if (shift > radius) return
    const bestShift = best ? Math.abs(best.value - point[axis]) : Infinity
    if (!best || shift < bestShift || (shift === bestShift && distance < best.distance)) best = { value, distance, guide }
  }
  for (const neighbor of neighbors) {
    const delta = neighbor[axis] - point[axis]
    const separation = Math.abs(neighbor[across] - point[across])
    if (Math.abs(delta) <= radius && separation > Math.max(point[acrossSpan], neighbor[acrossSpan])) {
      closestOnEachSide(aligned, neighbor, across, point[across])
    }
    if (separation <= radius && Math.abs(delta) > Math.max(point[span], neighbor[span])) {
      closestOnEachSide(straddling, neighbor, axis, point[axis])
    }
  }
  for (const neighbor of [aligned.before, aligned.after]) {
    if (!neighbor) continue
    const separation = Math.abs(neighbor[across] - point[across])
    consider(neighbor[axis], separation, {
      axis, value: neighbor[axis], from: Math.min(point[across], neighbor[across]), to: Math.max(point[across], neighbor[across]),
    })
  }
  const { before, after } = straddling
  if (before && after && Math.abs(before[across] - after[across]) <= radius) {
    const midpoint = (before[axis] + after[axis]) / 2
    const separation = after[axis] - before[axis]
    consider(midpoint, separation / 2, {
      axis: across, value: (before[across] + after[across]) / 2, from: before[axis], to: after[axis],
    })
  }
  return best
}

/** 生の座標を使って近傍の中心へ寄せ、候補のない軸だけを従来のグリッドに丸める。 */
export function snapGameFlowNode(position: XYPosition, shape: GameFlowShapeCenter | undefined,
  neighbors: readonly GameFlowShapeCenter[], view: View) {
  const center = shape && { ...shape, x: position.x + shape.x, y: position.y + shape.y }
  const { x: panX, y: panY, zoom, width, height } = view
  const visible = neighbors.filter(node => (node.x + node.halfWidth) * zoom + panX > 0
    && (node.x - node.halfWidth) * zoom + panX < width
    && (node.y + node.halfHeight) * zoom + panY > 0
    && (node.y - node.halfHeight) * zoom + panY < height)
  const radius = snapPixels / zoom
  const x = center && nearestCorrection(center, visible, 'x', radius)
  const y = center && nearestCorrection(center, visible, 'y', radius)
  const grid = (value: number) => Math.round(value / gridSize) * gridSize
  const guides: GameFlowSnapGuide[] = []
  if (x) guides.push(x.guide)
  if (y && !guides.some(guide => guide.axis === y.guide.axis && guide.value === y.guide.value
    && guide.from === y.guide.from && guide.to === y.guide.to)) guides.push(y.guide)
  return {
    position: {
      x: x && shape ? Math.round(x.value - shape.x) : grid(position.x),
      y: y && shape ? Math.round(y.value - shape.y) : grid(position.y),
    },
    guides,
  }
}
