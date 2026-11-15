import { expect, it, vi } from 'vitest'
import { circleIntersectsViewport, drawSpawnerRanges } from './spawnerRangeDrawing'

it('画面外中心でも円が画面に触れれば残し、角の外側だけを除外する', () => {
  expect(circleIntersectsViewport(-50, 100, 64, 400, 500)).toBe(true)
  expect(circleIntersectsViewport(450, 100, 64, 400, 500)).toBe(true)
  expect(circleIntersectsViewport(100, -50, 64, 400, 500)).toBe(true)
  expect(circleIntersectsViewport(100, 550, 64, 400, 500)).toBe(true)
  expect(circleIntersectsViewport(-50, -50, 64, 400, 500)).toBe(false)
  expect(circleIntersectsViewport(-64, 100, 64, 400, 500)).toBe(true)
  expect(circleIntersectsViewport(-66, 100, 64, 400, 500)).toBe(false)
})

it('10,000件を欠かさず独立した円で描き、strokeを3色の3回にまとめる', () => {
  const context = { beginPath: vi.fn(), moveTo: vi.fn(), arc: vi.fn(), stroke: vi.fn(), strokeStyle: '', lineWidth: 0 }
  const circles = Array.from({ length: 10_000 }, (_, index) => ({ x: index % 100, z: Math.floor(index / 100), radius: 32 + index % 3 * 16, color: ['#aa808080', '#bb808080', '#cc808080'][index % 3] }))
  drawSpawnerRanges(context as unknown as CanvasRenderingContext2D, circles)
  expect(context.arc).toHaveBeenCalledTimes(10_000)
  expect(context.stroke).toHaveBeenCalledTimes(3)
  expect(context.beginPath).toHaveBeenCalledTimes(3)
  expect(context.moveTo.mock.calls).toEqual(context.arc.mock.calls.map(([x, z, radius]) => [x + radius, z]))
  expect(new Set(context.arc.mock.calls.map(call => call.slice(0, 3).join(','))).size).toBe(10_000)
  console.info('Range load: 10,000 circles, 3 colors; arcs=10,000, strokes=3 (previous per-circle strokes=10,000).')
})
