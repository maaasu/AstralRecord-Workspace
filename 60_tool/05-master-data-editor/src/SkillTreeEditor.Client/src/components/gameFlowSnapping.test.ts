import { expect, it } from 'vitest'
import { gameFlowSnapNeighbors, measureGameFlowShapes, snapGameFlowNode, type GameFlowShapeCenter } from './gameFlowSnapping'

const center = (x: number, y: number): GameFlowShapeCenter => ({ x, y, halfWidth: 32, halfHeight: 32 })
const view = { x: 0, y: 0, zoom: 1, width: 800, height: 600 }
const anchor = center(0, 0)

it('aligns the two axes independently and uses the nearest eligible node on each side', () => {
  const point = { x: 201, y: 205 }
  const single = snapGameFlowNode(point, anchor, [center(197, 115), center(200, 50)], view)
  expect(single.position).toEqual({ x: 197, y: 210 })
  expect(single.guides).toEqual([{ axis: 'x', value: 197, from: 115, to: 205 }])
  const both = snapGameFlowNode(point, anchor, [center(197, 115), center(325, 203)], view)
  expect(both.position).toEqual({ x: 197, y: 203 })
  expect(both.guides).toHaveLength(2)
})

it('snaps to a row midpoint, a column midpoint, and both when four sides surround the anchor', () => {
  const raw = { x: 204, y: 206 }
  const row = [center(85, 202), center(321, 202)]
  const column = [center(203, 95), center(203, 309)]
  const horizontal = { axis: 'y', value: 202, from: 85, to: 321 }
  const vertical = { axis: 'x', value: 203, from: 95, to: 309 }
  expect(snapGameFlowNode(raw, anchor, row, view)).toEqual({ position: { x: 203, y: 202 }, guides: [horizontal] })
  expect(snapGameFlowNode(raw, anchor, column, view)).toEqual({ position: { x: 203, y: 202 }, guides: [vertical] })
  const four = snapGameFlowNode(raw, anchor, [...row, ...column], view)
  expect(four.position).toEqual({ x: 203, y: 202 })
  expect(four.guides).toEqual([vertical, horizontal])
})

it('draws a cross through the landed figure despite measured subpixel differences and the fractional zoom', () => {
  const shape = center(78.00005, 66.99996)
  const left = center(-109.001, 93.999)
  const right = center(291.002, 93.998)
  const top = center(91.002, -95.998)
  const bottom = center(91.002, 284.002)
  const zoomed = { ...view, x: 250, y: 200, zoom: 1.34241 }
  const result = snapGameFlowNode({ x: 14.8, y: 28.2 }, shape, [left, right, top, bottom], zoomed)
  expect(result.position).toEqual({ x: 13, y: 27 })
  expect(result.guides).toEqual([
    { axis: 'x', value: 13 + shape.x, from: top.y, to: bottom.y },
    { axis: 'y', value: 27 + shape.y, from: left.x, to: right.x },
  ])
})

it('omits a candidate guide when it would move the node to a different location', () => {
  const result = snapGameFlowNode({ x: 201, y: 205 }, anchor, [center(197, 115), center(202, 405)], view)
  expect(result.position).toEqual({ x: 202, y: 210 })
  expect(result.guides).toEqual([{ axis: 'x', value: 202, from: 205, to: 405 }])
})

it('considers a widely spaced pair in view but removes that midpoint when a candidate pans offscreen', () => {
  const raw = { x: 211, y: 203 }
  const pair = [center(-250, 200), center(650, 200)]
  const zoomedOut = { ...view, x: 180, zoom: .5 }
  expect(snapGameFlowNode(raw, anchor, pair, zoomedOut).position).toEqual({ x: 200, y: 200 })
  const panned = { ...zoomedOut, x: -125 }
  expect(snapGameFlowNode(raw, anchor, pair, panned).position).toEqual({ x: 210, y: 200 })
})

it('does not create a midpoint from diagonal or differently aligned pairs', () => {
  const raw = { x: 204, y: 200 }
  const diagonal = [center(100, 150), center(310, 245)]
  expect(snapGameFlowNode(raw, anchor, diagonal, view).guides).toEqual([])
  const misaligned = [center(100, 193), center(310, 208)]
  expect(snapGameFlowNode(raw, anchor, misaligned, view).position.x).toBe(200)
})

it('keeps the attraction at eight screen pixels and releases it onto the grid from raw coordinates', () => {
  const above = [center(178, 45)]
  expect(snapGameFlowNode({ x: 171, y: 208 }, anchor, above, view).position.x).toBe(178)
  const released = snapGameFlowNode({ x: 169, y: 208 }, anchor, above, view)
  expect(released.position.x).toBe(170)
  expect(released.guides).toEqual([])
  expect(snapGameFlowNode({ x: 169, y: 208 }, anchor, above, { ...view, zoom: .5 }).position.x).toBe(178)
  expect(snapGameFlowNode({ x: 173, y: 208 }, anchor, above, { ...view, zoom: 2 }).position.x).toBe(170)
})

it('measures the figure independent of a wider note or a label placed below its shape', () => {
  const canvas = document.createElement('div')
  canvas.innerHTML = `
    <div class="react-flow__node" data-id="note"><div class="game-flow-token-shape"></div></div>
    <div class="react-flow__node" data-id="class"><div class="game-flow-token-shape"></div><div class="game-flow-token-name"></div></div>
  `
  const note = canvas.querySelector<HTMLElement>('[data-id="note"]')!
  const profession = canvas.querySelector<HTMLElement>('[data-id="class"]')!
  const measure = (element: Element, rect: DOMRect) => Object.defineProperty(element, 'getBoundingClientRect', { value: () => rect })
  measure(note, new DOMRect(50, 20, 80, 67))
  measure(note.querySelector('.game-flow-token-shape')!, new DOMRect(54, 38, 70, 32))
  measure(profession, new DOMRect(180, 20, 120, 82))
  measure(profession.querySelector('.game-flow-token-shape')!, new DOMRect(193, 34, 36, 36))
  const shapes = measureGameFlowShapes(canvas, .5)
  expect(shapes.get('note')).toEqual({ x: 78, y: 68, halfWidth: 70, halfHeight: 32 })
  expect(shapes.get('class')).toEqual({ x: 62, y: 64, halfWidth: 36, halfHeight: 36 })
  expect(snapGameFlowNode({ x: 107, y: 42 }, shapes.get('class'), [center(173, 202)], view).position.x).toBe(111)
})

it('uses stationary real nodes as references and ignores a ghost and the entire moving group', () => {
  const position = { x: 50, y: 100 }
  const nodes = [
    { id: 'stationary', position, data: {} },
    { id: 'ghost', position, data: { suggestion: '' } },
    { id: 'dragged', position, data: {} },
    { id: 'grouped', position, data: {} },
    { id: 'hidden', position, data: {}, hidden: true },
  ]
  const shapes = new Map(nodes.map(node => [node.id, center(78, 67)]))
  expect(gameFlowSnapNeighbors(nodes, shapes, new Map([['dragged', position], ['grouped', position]]))).toEqual([center(128, 167)])
})
