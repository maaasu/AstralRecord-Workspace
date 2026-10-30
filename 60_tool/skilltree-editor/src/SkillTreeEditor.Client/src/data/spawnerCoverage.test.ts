import { expect, it } from 'vitest'
import { calculateCoverage, coverageTerrain, coversCellCenter, createCoverageCalculator, measuredCoverageLand } from './spawnerCoverage'
import type { PlanningCell } from './spawnerPlanning'

const bounds = { minX: -32, minZ: -16, width: 80, height: 48, cellSize: 16 }
const cell = (x: number, z: number): PlanningCell => ({ x, z, y: 70, status: 'ok', surface: 'grass_block', ground: 'dirt', biome: 'plains',
  safeCount: 256, waterCount: 0, treeCount: 0, candidateX: x, candidateY: 71, candidateZ: z, minY: 70, maxY: 70,
  habitat: 'grass', regionId: 'field', targetLevel: 15, reasonCode: 'eligible', reason: '' })
const cells = Array.from({ length: 15 }, (_, index) => cell(-32 + index % 5 * 16, -16 + Math.floor(index / 5) * 16))

it('直前の地形と円形状だけを再利用し、形状の書換え・重複数・地形・clearで更新する', () => {
  const calculator = createCoverageCalculator(), terrain = coverageTerrain(bounds, cells)
  const circle = { id: 'old', label: '森林狼', x: -8, z: 8, radius: 16 }
  const result = calculator.calculate(terrain, [circle])
  const renamed = { ...circle, id: 'new', label: '灰色狼' }
  expect(calculator.calculate(terrain, [renamed])).toBe(result)
  circle.x = 40
  const moved = calculator.calculate(terrain, [circle])
  expect(moved).not.toBe(result)
  expect(moved).toEqual(calculateCoverage(terrain, [circle]))
  for (const circles of [[{ ...circle, z: -8 }], [{ ...circle, radius: 32 }], [circle, circle], []]) {
    expect(calculator.calculate(terrain, circles)).toEqual(calculateCoverage(terrain, circles))
  }
  const original = calculator.calculate(terrain, [circle])
  const changedTerrain = coverageTerrain(bounds, cells.map(item => ({ ...item, status: 'failed' })))
  expect(calculator.calculate(changedTerrain, [circle]).counts.every(count => count === -1)).toBe(true)
  // An intervening/speculative key can evict the previous entry, never reuse it incorrectly.
  const restored = calculator.calculate(terrain, [circle])
  expect(restored).toEqual(original)
  calculator.clear()
  expect(calculator.calculate(terrain, [circle])).not.toBe(restored)
  expect(calculator.calculate(terrain, [circle])).toEqual(original)
})

it('地図ごとに独立して保持し、不正な円も通常計算と同じ結果になる', () => {
  const terrain = coverageTerrain(bounds, cells), first = createCoverageCalculator(), second = createCoverageCalculator()
  const circles = [{ x: NaN, z: 0, radius: 16 }, { x: 0, z: 0, radius: Infinity }]
  const result = first.calculate(terrain, circles)
  expect(second.calculate(terrain, circles)).not.toBe(result)
  expect(first.calculate(terrain, circles.map(circle => ({ ...circle })))).toBe(result)
  expect(result).toEqual(calculateCoverage(terrain, circles))
})

it('負座標・円周を含む0/1/2以上を幾何計算し、配置移動と半径変更を反映する', () => {
  const terrain = coverageTerrain(bounds, cells)
  const circle = { x: -8, z: 8, radius: 16 }
  const single = calculateCoverage(terrain, [circle])
  expect(single.counts[6]).toBe(1) // center
  expect(single.counts[5]).toBe(1) // exact left boundary
  expect(single.counts[1]).toBe(1) // exact north boundary
  expect(single.counts[0]).toBe(0) // diagonal outside
  expect(calculateCoverage(terrain, [circle, circle, circle]).counts[6]).toBe(2)
  expect(calculateCoverage(terrain, [{ ...circle, x: 40 }]).counts[6]).toBe(0)
  expect(calculateCoverage(terrain, [{ ...circle, radius: 1 }]).totals).toEqual([14, 1, 0])
  expect(calculateCoverage(terrain, []).totals).toEqual([15, 0, 0])
})

it('画面外中心から届く円と、地図全体を覆う巨大半径も正しく数える', () => {
  const terrain = coverageTerrain(bounds, cells)
  expect(calculateCoverage(terrain, [{ x: -48, z: -8, radius: 24 }]).counts[0]).toBe(1)
  expect(calculateCoverage(terrain, [{ x: 0, z: 0, radius: 1e12 }]).totals).toEqual([0, 15, 0])
  for (const radius of [0, -1, NaN, Infinity]) expect(calculateCoverage(terrain, [{ x: 0, z: 0, radius }]).totals).toEqual([15, 0, 0])
})

it('未解析・未保存・水域・候補不明・安全足場なしを未カバーへ誤分類しない', () => {
  const excluded = [
    { status: 'failed' }, { status: 'unknown' }, { status: 'absent' }, { habitat: 'water' }, { surface: 'water' },
    { surface: 'lava' }, { surface: null }, { surface: '' }, { habitat: 'unknown' }, { candidateX: null }, { candidateY: null }, { candidateZ: NaN }, { safeCount: 0 }, { safeCount: null },
  ].map((change, index) => ({ ...cell(index * 16, 0), ...change }))
  excluded.forEach(value => expect(measuredCoverageLand(value)).toBe(false))
  const terrain = coverageTerrain({ ...bounds, minX: 0, minZ: 0, width: excluded.length * 16, height: 16 }, excluded)
  expect([...calculateCoverage(terrain, []).counts]).toEqual(excluded.map(() => -1))
  expect(measuredCoverageLand({ ...cells[0], reasonCode: 'protected' })).toBe(true)
  expect(measuredCoverageLand({ ...cells[0], reasonCode: 'reserved_future' })).toBe(true)
})

it('行区間方式を全点幾何判定と照合する（接線・端・不規則な重なり）', () => {
  const circles = Array.from({ length: 37 }, (_, index) => ({ x: index * 11 % 160 - 70, z: index * 7 % 100 - 40, radius: index % 29 + .5 }))
  const result = calculateCoverage(coverageTerrain(bounds, cells), circles)
  expect([...result.counts]).toEqual(cells.map(item => Math.min(2, circles.filter(circle => Math.hypot(item.x + 8 - circle.x, item.z + 8 - circle.z) <= circle.radius).length)))
})

it('選択区画の詳細は円周・負座標・微小誤差を含めて着色の件数と一致する', () => {
  const terrain = coverageTerrain(bounds, cells)
  const circles = [
    { x: -8, z: 8, radius: 16 }, { x: -48, z: -8, radius: 24 },
    { x: -8 + 1e-10, z: 8, radius: 16 }, { x: 8, z: -80, radius: 72 },
    ...Array.from({ length: 37 }, (_, index) => ({ x: index * 11 % 160 - 70, z: index * 7 % 100 - 40, radius: index % 29 + .5 })),
  ]
  for (const circle of circles) {
    expect([...calculateCoverage(terrain, [circle]).counts]).toEqual(cells.map(item => Number(coversCellCenter(circle, item, bounds.cellSize))))
  }
})

it('約9万セル・1万円でも各円の局所的な行区間だけを加算する', () => {
  const large = { minX: -2400, minZ: -2400, width: 4800, height: 4800, cellSize: 16 }
  const land = Array.from({ length: 90000 }, (_, index) => cell(-2400 + index % 300 * 16, -2400 + Math.floor(index / 300) * 16))
  const circles = Array.from({ length: 10000 }, (_, index) => ({ x: -2300 + index % 100 * 46, z: -2300 + Math.floor(index / 100) * 46, radius: 64 }))
  const result = calculateCoverage(coverageTerrain(large, land), circles)
  expect(result.rowIntervals).toBeLessThanOrEqual(90000)
  expect(result.totals.reduce((sum, value) => sum + value, 0)).toBe(90000)
  expect(result.totals[2]).toBeGreaterThan(50000)
  console.info(`Coverage load: 90,000 cells / 10,000 circles; ${result.rowIntervals.toLocaleString('en-US')} row intervals instead of 900,000,000 cell-circle pairs.`)
})
