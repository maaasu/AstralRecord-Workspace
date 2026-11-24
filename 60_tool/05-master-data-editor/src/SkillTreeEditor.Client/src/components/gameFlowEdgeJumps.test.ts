import { getSmoothStepPath, Position } from '@xyflow/react'
import { expect, it } from 'vitest'
import { gameFlowEdgeRoute, gameFlowGapPath, gameFlowJumpPaths, gameFlowLabelPosition, type FlowEdgeKind, type FlowJumpDisplay } from './gameFlowEdgeJumps'

const route = (id: string, path: string, kind: FlowEdgeKind = 'progression') => gameFlowEdgeRoute(id, path, kind)
const gaps = (edge?: FlowJumpDisplay) => gameFlowGapPath(edge?.gaps ?? [])

it('adds a semicircle on a continuous line and masks only the contact with the lower line', () => {
  // React Flow の補助点が交点と重なっていても、同じ方向の直線はまとめて扱う。
  const horizontal = route('h', 'M0 50L50 50L100 50')
  const vertical = route('v', 'M50 0L50 100')
  const paths = gameFlowJumpPaths([horizontal, vertical], 6)
  expect(paths.get('h')?.path).toBe('M0 50L44 50A6 6 0 0 1 56 50L100 50')
  expect(paths.get('v')?.path).toBe(vertical.path)
  expect(gaps(paths.get('v'))).toBe('M50 41.5L50 46.5')
})

it('ignores shared endpoints, straight overlaps, rounded corners, and intersections in the same edge', () => {
  const edge = route('h', 'M0 50L95 50Q100 50 100 55L100 100')
  const endpoint = route('endpoint', 'M60 0L60 50')
  const overlap = route('overlap', 'M20 50L80 50')
  const corner = route('corner', 'M95 0L95 100')
  const self = route('self', 'M200 200L300 200L300 250L250 250L250 150')
  expect(gameFlowJumpPaths([edge, endpoint, overlap, corner, self], 6).size).toBe(0)
})

it('keeps multiple crossings separate and makes a reversed arrow jump on the same side', () => {
  const horizontal = route('h', 'M100 50L0 50')
  const vertical = route('v', 'M50 0L50 100')
  const other = route('other', 'M54 0L54 100')
  const paths = gameFlowJumpPaths([horizontal, vertical, other], 8)
  expect(paths.get('h')?.path).toBe('M100 50L56 50A2 2 0 0 0 52 50L52 50A2 2 0 0 0 48 50L0 50')
  expect(gaps(paths.get('v'))).toBe('M50 45.5L50 50.5')
  expect(gaps(paths.get('other'))).toBe('M54 45.5L54 50.5')
})

it('puts bridges on candidates and annotation lines before changing progression arrows', () => {
  const progression = route('h', 'M0 50L100 50')
  const annotation = route('annotation', 'M30 0L30 100', 'annotation')
  const candidate = route('candidate', 'M70 100L70 0', 'candidate')
  const paths = gameFlowJumpPaths([progression, annotation, candidate], 6)
  expect(paths.get('h')?.path).toBe(progression.path)
  expect(gaps(paths.get('h'))).toBe('M33.5 50L38.5 50M73.5 50L78.5 50')
  expect(paths.get('annotation')?.path).toBe('M30 0L30 44A6 6 0 0 1 30 56L30 100')
  expect(paths.get('candidate')?.path).toBe('M70 100L70 56A6 6 0 0 0 70 44L70 0')
})

it('keeps the original curve commands and recalculates when an edge moves or the radius changes', () => {
  const [rounded] = getSmoothStepPath({
    sourceX: -100, sourceY: -50, sourcePosition: Position.Right,
    targetX: 100, targetY: 50, targetPosition: Position.Left,
  })
  const bent = route('bent', rounded)
  const crossing = route('crossing', 'M-150 0L150 0')
  const result = gameFlowJumpPaths([crossing, bent], 6)
  expect(result.get('crossing')?.path).toContain('A6 6')
  expect(result.get('bent')?.path).toBe(rounded)
  expect(rounded).toContain('Q')
  expect(gameFlowJumpPaths([crossing, bent], 10).get('crossing')?.path).toContain('A10 10')
  expect(gameFlowJumpPaths([route('crossing', 'M-150 100L150 100'), bent], 6).size).toBe(0)
})

it('flips or shrinks a bridge whose original apex would touch the end of the lower line', () => {
  const horizontal = route('h', 'M0 50L100 50')
  const nearEndpoint = gameFlowJumpPaths([horizontal, route('v', 'M50 42L50 100')], 8)
  expect(nearEndpoint.get('h')?.path).toBe('M0 50L42 50A8 8 0 0 0 58 50L100 50')
  const gap = nearEndpoint.get('v')?.gaps?.[0]
  expect(gap).toBeDefined()
  expect((gap!.start + gap!.end) / 2).toBeCloseTo(58)

  const shortLower = gameFlowJumpPaths([horizontal, route('v', 'M50 46L50 56')], 8)
  expect(shortLower.get('h')?.path).toBe('M0 50L47 50A3 3 0 0 0 53 50L100 50')
  expect(shortLower.get('v')?.gaps).toEqual([{ axis: 'vertical', fixed: 50, start: 50.5, end: 55.5 }])
})

it('moves overlapping labels along their own line when it fits and leaves distant labels where they are', () => {
  const horizontal = route('h', 'M0 50L100 50')
  const vertical = route('v', 'M50 0L50 100')
  const paths = gameFlowJumpPaths([horizontal, vertical], 8)
  const label = { x: 50, y: 50 }
  expect(gameFlowLabelPosition(horizontal, label, 30, 12, paths.get('h')!.jumps!)).toEqual({ x: 24, y: 50 })
  expect(gameFlowLabelPosition(vertical, label, 30, 12, paths.get('v')!.jumps!)).toEqual({ x: 50, y: 59 })
  expect(gameFlowLabelPosition(horizontal, label, 45, 12, paths.get('h')!.jumps!)).toEqual({ x: 50, y: 59 })
  const distant = { x: 90, y: 50 }
  expect(gameFlowLabelPosition(horizontal, distant, 30, 12, paths.get('h')!.jumps!)).toBe(distant)
})
