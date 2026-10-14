import { describe, expect, it } from 'vitest'
import { classBonus, equipmentRows, equipmentStatRange, formatEquipmentRange, plainName, rangeOf } from './masterAnalytics'
import type { AnalyticsMaster } from './masterAnalytics'

describe('master comparison math', () => {
  it('shows only zero at probability one and ignores invalid range plus random drafts', () => {
    const random = { offsetMin: 1, offsetMax: 10, step: 1, zeroChance: 1 }
    expect(equipmentStatRange({ value: 0, random })).toEqual({ min: 0, max: 0, expected: 0 })
    expect(equipmentStatRange({ value: '0~0', random })).toBeNull()
    expect(equipmentStatRange({ value: { min: 0, max: 0 }, random })).toBeNull()
    expect(equipmentStatRange({ value: 0, min: 0, random })).toBeNull()
  })
  it('shows the creation roll bounds and expected value with a zero chance', () => {
    const master: AnalyticsMaster = { path: 'equipment.yml', progression: null, content: { equipment: {
      stats: [{ status: 'FIRE_DAMAGE_INCREASE', type: 'FLAT', value: 5,
        random: { offsetMin: -2, offsetMax: 3, step: 0.5, zeroChance: 0.2 } }],
    } } }
    const row = equipmentRows([master], 0)[0]
    expect(row.stats.FIRE_DAMAGE_INCREASE).toEqual({ min: 0, max: 8, expected: 4.4 })
    expect(formatEquipmentRange(row.stats.FIRE_DAMAGE_INCREASE)).toContain('期待値 4.4')
    expect(formatEquipmentRange({ min: 0.0001, max: 0.0002, expected: 0.00015 })).toContain('0.0001～0.0002')
    expect(row.rawStats[0]).toContain('zeroChance')
  })
  it('uses visible hex names in search, accessible labels and CSV rows', () => {
    expect(plainName('&#12abef黄金&x&5&5&f&f&5&5の剣')).toBe('黄金の剣')
    expect(equipmentRows([{ path: 'sword.yml', progression: null, content: { name: '§#12abef黄金§rの剣' } }], 0)[0].name).toBe('黄金の剣')
  })
  it('retains both possible endpoints of independent equipment rolls', () => {
    expect(rangeOf({ min: '10~20', max: '21~50' })).toEqual({ min: 10, max: 50 })
    expect(rangeOf('20～10')).toEqual({ min: 10, max: 20 })
    expect(rangeOf('unknown')).toBeNull()
    expect(rangeOf('')).toBeNull()
  })
  it('accumulates enhancement and multiplies scalar ranges within one item', () => {
    const master: AnalyticsMaster = { path: 'equipment.yml', progression: 1, content: { id: '20a00001', equipment: {
      stats: [{ status: 'ATTACK', type: 'FLAT', value: '10~20' }, { status: 'ATTACK', type: 'SCALAR', value: '0.5~2' }, { status: 'DEFENSE', type: 'SCALAR', value: 1 }],
      enhance: { maxLevel: 2, levels: [{ level: 1, statIncrease: [{ status: 'ATTACK', type: 'FLAT', value: 3 }] }, { level: 2, statIncrease: [{ status: 'ATTACK', type: 'FLAT', value: 4 }] }] },
    } } }
    expect(equipmentRows([master], 1)[0].stats).toEqual({ ATTACK: { min: 6.5, max: 46 } })
    expect(equipmentRows([master], 99)[0].stats.ATTACK).toEqual({ min: 8.5, max: 54 })
  })
  it('uses the first class stat, applies growth from Lv1 and clamps to maxLevel', () => {
    const master: AnalyticsMaster = { path: 'class.yml', progression: null, content: { maxLevel: 3, baseStats: [{ status: 'attack', value: 10 }, { status: 'ATTACK', value: 99 }], growthPerLevel: [{ status: 'ATTACK', value: 1.5 }] } }
    expect(classBonus(master, 'ATTACK', 1)).toBe(10)
    expect(classBonus(master, 'ATTACK', 99)).toBe(13)
    expect(classBonus(master, 'DEFENSE', 3)).toBe(0)
  })
  it('replaces base scalar with the cumulative enhancement scalar', () => {
    const master: AnalyticsMaster = { path: 'equipment.yml', progression: null, content: { equipment: {
      stats: [{ status: 'ATTACK', type: 'FLAT', value: 10 }, { status: 'ATTACK', type: 'SCALAR', value: 0.5 }, { status: 'ATTACK', type: 'SCALAR', value: 1 }],
      enhance: { maxLevel: 2, levels: [{ level: 1, statIncrease: [{ status: 'ATTACK', type: 'SCALAR', value: 2 }] }, { level: 2, statIncrease: [{ status: 'ATTACK', type: 'SCALAR', value: 0.5 }] }] },
    } } }
    expect(equipmentRows([master], 0)[0].stats.ATTACK).toEqual({ min: 10, max: 10 })
    expect(equipmentRows([master], 1)[0].stats.ATTACK).toEqual({ min: 20, max: 20 })
    expect(equipmentRows([master], 2)[0].stats.ATTACK).toEqual({ min: 25, max: 25 })
  })
})
