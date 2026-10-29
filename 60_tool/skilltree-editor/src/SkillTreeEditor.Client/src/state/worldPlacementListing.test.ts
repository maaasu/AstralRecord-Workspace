import { describe, expect, it } from 'vitest'
import type { WorldPlacement } from '../api/worldPlacementApi'
import { sortWorldPlacementListing, type PlacementListingInfo, type PlacementListingSort } from './worldPlacementListing'

const placement = (id: string, x: number, kind: WorldPlacement['kind'] = 'spawner'): WorldPlacement => ({
  placementId: id, kind, worldId: 'eriva', worldName: 'worlds/eriva', templateId: id,
  x, y: 70, z: 0, yaw: 0, pitch: 0, enabled: true, waystone: null,
})
const ids = (items: readonly WorldPlacement[]) => items.map(item => item.placementId)
const rows = [placement('mixed', 20), placement('unknown', 10), placement('low', 30), placement('npc', 5, 'npc'), placement('same-a', 10), placement('same-b', 10)]
const display = new Map<string, PlacementListingInfo>([
  ['mixed', { name: '&a狼10', levels: [10, 40] }],
  ['unknown', { name: '狼2', levels: [] }],
  ['low', { name: 'あいう', levels: [5] }],
  ['npc', { name: '村人', levels: [] }],
  ['same-a', { name: '§b狼2', levels: [10] }],
  ['same-b', { name: '&c狼2', levels: [10] }],
])
const sorted = (mode: PlacementListingSort) => sortWorldPlacementListing(rows, mode, display, { x: 0, z: 0 })

describe('ワールド配置の一覧順', () => {
  it('標準順は元の参照と順序を維持し、入力配列を変更しない', () => {
    const original = [...rows]
    const result = sorted('default')
    expect(ids(result.items)).toEqual(ids(rows))
    expect(result.items).not.toBe(rows)
    expect(rows).toEqual(original)
    expect(rows.every((item, index) => item === original[index])).toBe(true)
  })
  it('開始地点からの水平距離で並び、同距離は元順を維持する', () => {
    const result = sorted('distance')
    expect(ids(result.items)).toEqual(['npc', 'unknown', 'same-a', 'same-b', 'mixed', 'low'])
    expect(result.distances.get('mixed')).toBe(20)
  })
  it('低順は最小実レベル、高順は最大実レベルを使い、不明は末尾にする', () => {
    expect(ids(sorted('level-asc').items)).toEqual(['low', 'mixed', 'same-a', 'same-b', 'unknown', 'npc'])
    expect(ids(sorted('level-desc').items)).toEqual(['mixed', 'same-a', 'same-b', 'low', 'unknown', 'npc'])
  })
  it('色コードを無視した日本語の自然順で、同名は元順を維持する', () => {
    expect(ids(sorted('name').items)).toEqual(['low', 'npc', 'unknown', 'same-a', 'same-b', 'mixed'])
  })
  it('非有限の座標と実レベルは末尾に置き、どの並び順でも入力を変更しない', () => {
    const invalid = { ...placement('invalid', Number.NaN), z: Number.POSITIVE_INFINITY }
    const finite = placement('finite', 4)
    const source = Object.freeze([Object.freeze(invalid), Object.freeze(finite)])
    const info = new Map<string, PlacementListingInfo>([
      ['invalid', { name: '不明', levels: [Number.NaN, Number.POSITIVE_INFINITY] }],
      ['finite', { name: '既知', levels: [7] }],
    ])
    for (const mode of ['distance', 'level-asc', 'level-desc', 'name'] as const) {
      const result = sortWorldPlacementListing(source, mode, info, { x: 0, z: 0 })
      expect(result.items).toHaveLength(2)
      expect(source[0]).toBe(invalid)
      expect(source[1]).toBe(finite)
      if (mode !== 'name') expect(ids(result.items)).toEqual(['finite', 'invalid'])
    }
    expect(sortWorldPlacementListing(source, 'distance', info, { x: 0, z: 0 }).distances.has('invalid')).toBe(false)
  })
})
