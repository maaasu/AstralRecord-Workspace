import { expect, it } from 'vitest'
import { sortEquipment } from './equipmentSort'
import type { EquipmentRow } from './masterAnalytics'

const row = (id: string, fields: Partial<EquipmentRow> = {}): EquipmentRow => ({ id, path: `${id}.yml`, name: id, level: 10, progression: null, stats: {}, slot: '', tag: '', hand: '', rarity: '', requiredClasses: '', rawStats: [], ...fields })
const ids = (rows: EquipmentRow[]) => rows.map(item => item.id)
it('下限・上限を数値で比較し、0・負値・未定義・同値を扱い入力を変更しない', () => {
  const input = [row('wide', { stats: { HP: { min: 1, max: 100 } } }), row('none'), row('zero', { stats: { HP: { min: 0, max: 0 } } }), row('fixed', { stats: { HP: { min: 5, max: 5 } } }), row('negative', { stats: { HP: { min: -5, max: -1 } } }), row('tie', { stats: { HP: { min: 5, max: 5 } } })]
  expect(ids(sortEquipment(input, 'min', 'desc', 'HP'))).toEqual(['fixed', 'tie', 'wide', 'zero', 'negative', 'none'])
  expect(ids(sortEquipment(input, 'max', 'desc', 'HP'))).toEqual(['wide', 'fixed', 'tie', 'zero', 'negative', 'none'])
  expect(ids(sortEquipment(input, 'min', 'asc', 'HP'))).toEqual(['negative', 'zero', 'wide', 'fixed', 'tie', 'none'])
  expect(ids(sortEquipment(input, 'max', 'asc', 'HP'))).toEqual(['negative', 'zero', 'fixed', 'tie', 'wide', 'none'])
  expect(sortEquipment(input, 'default', 'desc', 'HP')).toBe(input)
  expect(ids(sortEquipment(input, 'max', 'desc', 'missing'))).toEqual(ids(input))
  expect(ids(input)).toEqual(['wide', 'none', 'zero', 'fixed', 'negative', 'tie'])
})

it('要求Lv・進行度の未定義と非有限値を末尾にし、名前・IDを自然順で比較する', () => {
  const input = [row('gear10', { name: '剣10', level: 20, progression: 2 }), row('gear2', { name: '剣2', level: 5, progression: 0 }), row('gear1', { name: '剣1', progression: NaN }), row('gear3', { name: '剣3', progression: null })]
  expect(ids(sortEquipment(input, 'level', 'asc', ''))).toEqual(['gear2', 'gear1', 'gear3', 'gear10'])
  expect(ids(sortEquipment(input, 'progression', 'desc', ''))).toEqual(['gear10', 'gear2', 'gear1', 'gear3'])
  expect(ids(sortEquipment(input, 'progression', 'asc', ''))).toEqual(['gear2', 'gear10', 'gear1', 'gear3'])
  expect(ids(sortEquipment(input, 'name', 'asc', ''))).toEqual(['gear1', 'gear2', 'gear3', 'gear10'])
  expect(ids(sortEquipment(input, 'id', 'desc', ''))).toEqual(['gear10', 'gear3', 'gear2', 'gear1'])
  expect(ids(sortEquipment([row('inf', { stats: { HP: { min: Infinity, max: Infinity } } }), row('zero', { stats: { HP: { min: 0, max: 0 } } })], 'max', 'desc', 'HP'))).toEqual(['zero', 'inf'])
})
