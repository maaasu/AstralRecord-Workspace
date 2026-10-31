import { describe, expect, it } from 'vitest'
import { differingEquipmentStatuses } from './equipmentComparisonColumns'
import type { EquipmentRow } from './masterAnalytics'

describe('equipment comparison columns', () => {
  it('keeps missing versus zero, bounds, expectation presence and unrounded differences distinct', () => {
    const rows: Pick<EquipmentRow, 'stats'>[] = [
      { stats: { same: { min: -2, max: 5 }, zero: { min: 0, max: 0 }, lower: { min: 0, max: 5 }, upper: { min: 0, max: 5 }, mean: { min: 0, max: 5, expected: 2 }, presence: { min: 0, max: 5 }, precise: { min: 1, max: 1 } } },
      { stats: { same: { min: -2, max: 5 }, lower: { min: 1, max: 5 }, upper: { min: 0, max: 6 }, mean: { min: 0, max: 5, expected: 3 }, presence: { min: 0, max: 5, expected: 0 }, precise: { min: 1, max: 1.0000001 } } },
    ]
    const order = ['precise', 'same', 'zero', 'lower', 'upper', 'mean', 'presence']
    expect(differingEquipmentStatuses(rows, order)).toEqual(['precise', 'zero', 'lower', 'upper', 'mean', 'presence'])
    expect(differingEquipmentStatuses([...rows].reverse(), order)).toEqual(differingEquipmentStatuses(rows, order))
  })

  it('keeps all supplied columns for fewer than two rows and does not change inputs', () => {
    const statuses = Object.freeze(['b', 'a'])
    const rows = Object.freeze([{ stats: Object.freeze({ a: { min: 1, max: 1 } }) }])
    expect(differingEquipmentStatuses([], statuses)).toEqual(['b', 'a'])
    expect(differingEquipmentStatuses(rows, statuses)).toEqual(['b', 'a'])
    expect(differingEquipmentStatuses([rows[0], rows[0]], statuses)).toEqual([])
  })
})
