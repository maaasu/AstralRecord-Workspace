import { expect, it } from 'vitest'
import { createMapLabelIndex, type MapLabelBox } from './mapLabelIndex'

const overlaps = (candidate: MapLabelBox, label: MapLabelBox) => candidate.x < label.x + label.width + 4 && candidate.x + candidate.width + 4 > label.x && Math.abs(candidate.z - label.z) < 22

it('22pxの縦境界と4pxの横余白は以前と同じ開区間で判定する', () => {
  const index = createMapLabelIndex()
  index.add({ x: 0, z: 21.9, width: 100 })
  expect(index.overlaps({ x: 0, z: 22.1, width: 100 })).toBe(true)
  expect(index.overlaps({ x: 0, z: -.1, width: 100 })).toBe(false)
  expect(index.overlaps({ x: 0, z: -.09, width: 100 })).toBe(true)
  expect(index.overlaps({ x: 104, z: 21.9, width: 100 })).toBe(false)
  expect(index.overlaps({ x: 103.99, z: 21.9, width: 100 })).toBe(true)
  expect(index.overlaps({ x: -104, z: 21.9, width: 100 })).toBe(false)
  expect(index.overlaps({ x: -103.99, z: 21.9, width: 100 })).toBe(true)
})

it('負数・小数と行境界をまたぐ座標でも線形走査と同じ判定になる', () => {
  const index = createMapLabelIndex(), drawn: MapLabelBox[] = []
  for (let i = 0; i < 3000; i++) {
    const candidate = { x: (i * 137 % 2500) / 2 - 600, z: (i * 73 % 1900) / 3 - 300, width: 12 + (i * 31 % 240) }
    const collision = drawn.some(label => overlaps(candidate, label))
    expect(index.overlaps(candidate)).toBe(collision)
    if (!collision) { index.add(candidate); drawn.push(candidate) }
  }
})

it('上下端で丸めた候補と強制表示の選択ラベルを同じ順で描ける', () => {
  const index = createMapLabelIndex(), drawn: MapLabelBox[] = [], accepted: number[] = []
  const candidates = Array.from({ length: 400 }, (_, i) => ({ x: Math.max(4, Math.min(250, i * 53 % 500)), z: Math.max(4, Math.min(480, i * 89 % 800 - 120)), width: 132 }))
  candidates.forEach((candidate, i) => {
    const selected = i === candidates.length - 1
    const expected = selected || !drawn.some(label => overlaps(candidate, label))
    expect(selected || !index.overlaps(candidate)).toBe(expected)
    if (expected) { index.add(candidate); drawn.push(candidate); accepted.push(i) }
  })
  expect(accepted.at(-1)).toBe(399)
  expect(createMapLabelIndex().overlaps(candidates[0])).toBe(false)
})

it('2500候補の表示を維持し、離れた行の比較を減らす', () => {
  const candidates = Array.from({ length: 2500 }, (_, i) => ({ x: i % 50 * 24, z: Math.floor(i / 50) * 14, width: 110 }))
  const run = (indexed: boolean) => {
    const index = createMapLabelIndex(), drawn: MapLabelBox[] = [], accepted: number[] = []
    let comparisons = 0
    candidates.forEach((candidate, i) => {
      if (indexed ? index.overlaps(candidate) : drawn.some(label => overlaps(candidate, label))) return
      // Every exact rectangle predicate reads the stored width once. Indexing
      // itself only reads z; the getter counts work without a production counter.
      const stored = { x: candidate.x, z: candidate.z, get width() { comparisons++; return candidate.width } }
      index.add(stored); drawn.push(stored); accepted.push(i)
    })
    return { comparisons, accepted }
  }
  const before = run(false), after = run(true)
  expect(after.accepted).toEqual(before.accepted)
  expect(after.comparisons).toBeLessThan(before.comparisons / 10)
  console.info(JSON.stringify({ labelIndexBenchmark: true, candidates: candidates.length, labels: after.accepted.length, beforeComparisons: before.comparisons, afterComparisons: after.comparisons }))
})
