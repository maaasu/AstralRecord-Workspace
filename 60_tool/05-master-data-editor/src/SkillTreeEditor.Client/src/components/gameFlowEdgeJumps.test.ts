import { getSmoothStepPath, Position } from '@xyflow/react'
import { expect, it } from 'vitest'
import { gameFlowEdgeRoute, gameFlowJumpPaths, type FlowEdgeKind } from './gameFlowEdgeJumps'

const route = (id: string, path: string, kind: FlowEdgeKind = 'progression') => gameFlowEdgeRoute(id, path, kind)

it('adds a semicircle on a continuous line and masks only the contact with the lower line', () => {
  // React Flow の補助点が交点と重なっていても、同じ方向の直線はまとめて扱う。
  const horizontal = route('h', 'M0 50L50 50L100 50')
  const vertical = route('v', 'M50 0L50 100')
  const paths = gameFlowJumpPaths([horizontal, vertical], 6)
  expect(paths.get('h')).toEqual({ path: 'M0 50L44 50A6 6 0 0 1 56 50L100 50' })
  expect(paths.get('v')).toEqual({ path: vertical.path, gapPath: 'M50 41.5L50 46.5' })
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
  expect(paths.get('v')?.gapPath).toBe('M50 45.5L50 50.5')
  expect(paths.get('other')?.gapPath).toBe('M54 45.5L54 50.5')
})

it('puts bridges on candidates and annotation lines before changing progression arrows', () => {
  const progression = route('h', 'M0 50L100 50')
  const annotation = route('annotation', 'M30 0L30 100', 'annotation')
  const candidate = route('candidate', 'M70 100L70 0', 'candidate')
  const paths = gameFlowJumpPaths([progression, annotation, candidate], 6)
  expect(paths.get('h')).toEqual({ path: progression.path, gapPath: 'M33.5 50L38.5 50M73.5 50L78.5 50' })
  expect(paths.get('annotation')).toEqual({ path: 'M30 0L30 44A6 6 0 0 1 30 56L30 100' })
  expect(paths.get('candidate')).toEqual({ path: 'M70 100L70 56A6 6 0 0 0 70 44L70 0' })
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
