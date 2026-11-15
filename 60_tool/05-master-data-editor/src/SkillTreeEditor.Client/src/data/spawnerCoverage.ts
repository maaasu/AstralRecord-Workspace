import type { PlanningBounds, PlanningCell } from './spawnerPlanning'

export interface CoverageCircle { x: number; z: number; radius: number }
export interface CoverageSource extends CoverageCircle { id: string; label: string }
export const coverageColors = ['#ef923fc0', '#5fd2a57a', '#c276f0cf'] as const

// Match the row-interval calculation's inclusive edge and horizontal grid tolerance.
export function coversCellCenter(circle: CoverageCircle, cell: PlanningCell, step: number) {
  const dz = cell.z + step / 2 - circle.z
  if (Math.abs(dz) > circle.radius) return false
  const dx = Math.sqrt(Math.max(0, circle.radius * circle.radius - dz * dz))
  const x = cell.x + step / 2, tolerance = step * 1e-10
  return x >= circle.x - dx - tolerance && x <= circle.x + dx + tolerance
}

export function measuredCoverageLand(cell: PlanningCell) {
  return cell.status === 'ok' && cell.habitat !== 'water' && cell.habitat !== 'unknown' &&
    typeof cell.surface === 'string' && cell.surface.length > 0 && !/water|lava/.test(cell.surface) &&
    typeof cell.safeCount === 'number' && Number.isFinite(cell.safeCount) && cell.safeCount > 0 &&
    [cell.candidateX, cell.candidateY, cell.candidateZ].every(value => typeof value === 'number' && Number.isFinite(value))
}

// Index the measured terrain once. Unmeasured/water/unsafe cells never become holes.
export function coverageTerrain(bounds: PlanningBounds, cells: PlanningCell[]) {
  const width = Math.ceil(bounds.width / bounds.cellSize), height = Math.ceil(bounds.height / bounds.cellSize)
  const indices = new Int32Array(width * height).fill(-1)
  cells.forEach((cell, index) => {
    if (!measuredCoverageLand(cell)) return
    const x = (cell.x - bounds.minX) / bounds.cellSize, z = (cell.z - bounds.minZ) / bounds.cellSize
    if (Number.isInteger(x) && Number.isInteger(z) && x >= 0 && x < width && z >= 0 && z < height) indices[z * width + x] = index
  })
  return { bounds, width, height, indices, cellCount: cells.length }
}

// A circle contributes a contiguous interval per intersected grid row. Difference
// arrays avoid a terrain-cell × spawner scan, including dense or very large circles.
export function calculateCoverage(terrain: ReturnType<typeof coverageTerrain>, circles: CoverageCircle[]) {
  const { bounds, width, height, indices, cellCount } = terrain
  const step = bounds.cellSize, stride = width + 1, changes = new Int32Array(stride * height)
  let rowIntervals = 0
  for (const circle of circles) {
    if (![circle.x, circle.z, circle.radius].every(Number.isFinite) || circle.radius <= 0) continue
    const start = Math.max(0, Math.ceil((circle.z - circle.radius - bounds.minZ) / step - .5))
    const end = Math.min(height - 1, Math.floor((circle.z + circle.radius - bounds.minZ) / step - .5))
    for (let z = start; z <= end; z++) {
      const dz = bounds.minZ + (z + .5) * step - circle.z
      const dx = Math.sqrt(Math.max(0, circle.radius * circle.radius - dz * dz))
      const left = Math.max(0, Math.ceil((circle.x - dx - bounds.minX) / step - .5 - 1e-10))
      const right = Math.min(width - 1, Math.floor((circle.x + dx - bounds.minX) / step - .5 + 1e-10))
      if (left > right) continue
      changes[z * stride + left]++
      changes[z * stride + right + 1]--
      rowIntervals++
    }
  }
  // -1 means outside the measured land; 2 deliberately means two or more.
  const counts = new Int8Array(cellCount).fill(-1), totals = [0, 0, 0]
  for (let z = 0; z < height; z++) {
    let count = 0
    for (let x = 0; x < width; x++) {
      count += changes[z * stride + x]
      const index = indices[z * width + x]
      if (index < 0) continue
      const value = Math.min(2, count)
      counts[index] = value
      totals[value]++
    }
  }
  return { counts, totals, rowIntervals }
}

// One entry per map: metadata edits only compare O(circles) numbers, rather than
// rebuilding the terrain-wide arrays. Terrain and returned results are immutable.
// A speculative React render may evict this entry, but cannot return a wrong key.
export function createCoverageCalculator() {
  let previous: { terrain: ReturnType<typeof coverageTerrain>; circles: CoverageCircle[]; result: ReturnType<typeof calculateCoverage> } | null = null
  return {
    clear() { previous = null },
    calculate(terrain: ReturnType<typeof coverageTerrain>, circles: CoverageCircle[]) {
      if (previous?.terrain === terrain && previous.circles.length === circles.length && circles.every((circle, index) => {
        const prior = previous!.circles[index]
        return Object.is(circle.x, prior.x) && Object.is(circle.z, prior.z) && Object.is(circle.radius, prior.radius)
      })) return previous.result
      const result = calculateCoverage(terrain, circles)
      // Copy geometry so a caller's later edit cannot silently alter the cache key.
      previous = { terrain, circles: circles.map(({ x, z, radius }) => ({ x, z, radius })), result }
      return result
    },
  }
}
