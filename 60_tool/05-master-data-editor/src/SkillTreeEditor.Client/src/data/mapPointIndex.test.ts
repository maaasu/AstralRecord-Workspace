import { expect, it } from 'vitest'
import { createMapPointIndex } from './mapPointIndex'

const points = Array.from({ length: 2500 }, (_, id) => ({ id, x: (id % 50 - 25) * 64 - .5, z: (Math.floor(id / 50) - 25) * 64 + .25 }))

it('includes every possible hit across negative/fractional bucket edges, without duplicate visits', () => {
  const index = createMapPointIndex(points)
  for (const scale of [.02, .25, .7, 2, 16]) for (const x of [-128.00001, -64, -.5, 0, 63.99999, 64, 128.25]) {
    const radius = 8 / scale, z = -x
    const orders: number[] = []
    index.forEachNear(x, z, radius, (item, order) => {
      expect(item).toBe(points[order])
      orders.push(order)
    })
    expect(new Set(orders).size).toBe(orders.length)
    const candidates = new Set(orders)
    points.forEach((point, order) => {
      if (Math.hypot(point.x - x, point.z - z) < radius) expect(candidates.has(order)).toBe(true)
    })
  }
})

it('matches a full nearest-point scan including opposite tie orders after projection changes', () => {
  const data = [...points, { id: 2500, x: -.5, z: .25 }, { id: 2501, x: -.5, z: .25 }]
  const index = createMapPointIndex(data)
  for (const scale of [.02, .25, .7, 2, 16]) for (const preferLast of [false, true]) for (let query = 0; query <= 40; query++) {
    const point = query === 40 ? data[data.length - 1] : data[query * 61 % data.length]
    const x = point.x + (query === 40 ? 0 : .01 * query), z = point.z
    const minX = -2400, minZ = -2400, offsetX = 203 - (x + .5 - minX) * scale, offsetZ = 250 - (z + .5 - minZ) * scale
    const distance = (point: typeof data[number]) => Math.hypot((point.x + .5 - minX) * scale + offsetX - 203, (point.z + .5 - minZ) * scale + offsetZ - 250)
    let expected: number | undefined, best = 8
    for (const order of data.map((_, order) => order).sort((a, b) => preferLast ? b - a : a - b)) {
      const next = distance(data[order])
      if (next < best) { best = next; expected = order }
    }
    let actual: number | undefined; best = 8
    index.forEachNear(x, z, 8 / scale, (point, order) => {
      const next = distance(point)
      if (next < best || (actual !== undefined && next === best && (preferLast ? order > actual : order < actual))) { best = next; actual = order }
    })
    expect(actual).toBe(expected)
  }
})

it.each([[0, 0, 1e300], [Infinity, 0, 8], [NaN, 0, 8], [0, 0, Infinity], [0, 0, -1]])('bounds query work for extreme/invalid query %s,%s,%s', (x, z, radius) => {
  const visits: number[] = []
  createMapPointIndex(points).forEachNear(x, z, radius, (_, order) => visits.push(order))
  expect(visits.sort((a, b) => a - b)).toEqual(points.map((_, order) => order))
})

it('falls back for unsafe point coordinates and supports empty or nonfinite data', () => {
  const unsafe = [...points, { id: -1, x: 1e300, z: 0 }], visits: number[] = []
  createMapPointIndex(unsafe).forEachNear(0, 0, 8, (_, order) => visits.push(order))
  expect(visits).toHaveLength(unsafe.length)
  createMapPointIndex([]).forEachNear(0, 0, 8, () => { throw new Error('Empty index visited a point') })
  const invalid = { x: NaN, z: Infinity }
  const mixed = createMapPointIndex([...points, invalid])
  mixed.forEachNear(0, 0, 8, item => expect(item).not.toBe(invalid))
})

it('reuses indexed coordinates and bounds candidate work for a sparse local query', () => {
  let reads = 0, visits = 0
  const data = points.map(point => ({ get x() { reads++; return point.x }, get z() { reads++; return point.z } }))
  const index = createMapPointIndex(data)
  expect(reads).toBeGreaterThan(0)
  reads = 0
  for (let query = 0; query < 100; query++) index.forEachNear(query, -query, 4, () => visits++)
  expect(reads).toBe(0)
  expect(visits).toBeLessThan(1600)
  console.info(`Point index: 100 local queries / 2500 points, candidates=${visits}, full scan=250000`)
})
